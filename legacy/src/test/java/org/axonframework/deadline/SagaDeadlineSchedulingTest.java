/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.deadline;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.messaging.core.Scope;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaScopeDescriptor;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.configuration.Sagas;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Shows that a Saga schedules deadlines the way it did in Axon Framework 4: with the {@link DeadlineManager} from the
 * configuration, however the Saga reaches it, within the Saga's own scope, and only once the Saga has been stored in
 * the same unit of work.
 */
class SagaDeadlineSchedulingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");

    private final InMemorySagaStore sagaStore = new InMemorySagaStore();
    private final RecordingDeadlineManager deadlineManager = new RecordingDeadlineManager(sagaStore);

    private @Nullable AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
        // A scope left behind would leak into whichever test runs next on this thread.
        assertThatThrownBy(Scope::getCurrentScope).isInstanceOf(IllegalStateException.class);
    }

    @Nested
    class ThroughAHandlerParameter {

        @Test
        void theParameterResolvesToTheConfiguredDeadlineManager() {
            // given
            startWith(Sagas.of(ParameterSaga.class));

            // when
            publish(asEventMessage(new OrderPlaced("order-1")));

            // then
            assertThat(deadlineManager.scheduled).singleElement()
                                                 .extracting(ScheduledDeadline::deadlineName)
                                                 .isEqualTo("paymentReminder");
        }

        @Test
        void theDeadlineIsScheduledWithinTheScopeOfTheSaga() {
            // given
            startWith(Sagas.of(ParameterSaga.class));

            // when
            publish(asEventMessage(new OrderPlaced("order-1")));

            // then
            String sagaId = sagaIdOf(ParameterSaga.class);
            assertThat(deadlineManager.scheduled).singleElement()
                                                 .extracting(ScheduledDeadline::scope)
                                                 .isEqualTo(new SagaScopeDescriptor("ParameterSaga", sagaId));
        }

        @Test
        void theDeadlineIsScheduledOnceTheSagaHasBeenStored() {
            // given
            startWith(Sagas.of(ParameterSaga.class));

            // when
            publish(asEventMessage(new OrderPlaced("order-1")));

            // then
            assertThat(deadlineManager.scheduled).singleElement()
                                                 .satisfies(deadline -> assertThat(deadline.sagaStoredWhenScheduled())
                                                         .isTrue());
        }

        /**
         * The Axon Framework 4 counterpart is {@code deadlineCancellationWithinScopeOnSaga}: a scope-less
         * {@code cancelAllWithinScope(name)} from a Saga handler cancels within that Saga's scope.
         */
        @Test
        void cancelAllWithinScopeWithoutAScopeDescriptorCancelsWithinTheScopeOfTheSaga() {
            // given
            startWith(Sagas.of(ParameterSaga.class));
            publish(asEventMessage(new OrderPlaced("order-1")));

            // when
            publish(asEventMessage(new OrderPaid("order-1")));

            // then
            String sagaId = sagaIdOf(ParameterSaga.class);
            assertThat(deadlineManager.cancelledWithinScope).containsExactly(
                    "paymentReminder@" + new SagaScopeDescriptor("ParameterSaga", sagaId).scopeDescription()
            );
        }

        /**
         * An event published within a unit of work reaches a subscribing processor while that unit of work runs its
         * prepare-commit phase, which is where the Saga then schedules its deadline from.
         */
        @Test
        void aSagaInvokedWhileTheUnitOfWorkPreparesItsCommitSchedulesToo() {
            // given
            startWith(Sagas.of(ParameterSaga.class));
            UnitOfWorkFactory unitOfWorkFactory = configuration().getComponent(UnitOfWorkFactory.class);
            EventSink eventSink = configuration().getComponent(EventSink.class);

            // when
            FutureUtils.joinAndUnwrap(
                    unitOfWorkFactory.create().executeWithResult(
                            context -> eventSink.publish(context, List.of(asEventMessage(new OrderPlaced("order-1"))))
                    ),
                    TIMEOUT
            );

            // then
            String sagaId = sagaIdOf(ParameterSaga.class);
            assertThat(deadlineManager.scheduled).singleElement()
                                                 .satisfies(deadline -> {
                                                     assertThat(deadline.scope()).isEqualTo(
                                                             new SagaScopeDescriptor("ParameterSaga", sagaId)
                                                     );
                                                     assertThat(deadline.sagaStoredWhenScheduled()).isTrue();
                                                 });
        }
    }

    @Nested
    class CorrelationData {

        @Test
        void theDeadlineCarriesTheCorrelationDataOfTheEventTheSagaHandled() {
            // given
            startWith(Sagas.of(ParameterSaga.class));
            EventMessage event = asEventMessage(new OrderPlaced("order-1"));

            // when
            publish(event);

            // then
            assertThat(deadlineManager.scheduled).singleElement()
                                                 .extracting(ScheduledDeadline::metadata)
                                                 .satisfies(metadata -> assertThat(metadata)
                                                         .containsEntry("correlationId", event.identifier())
                                                         .containsEntry("causationId", event.identifier()));
        }
    }

    /**
     * Axon Framework 4 users often kept the {@code DeadlineManager} in a service the Saga delegates to. Such a
     * collaborator is not a handler parameter, yet it still schedules within the Saga's scope and deferred to the
     * Saga's unit of work.
     */
    @Nested
    class ThroughACollaborator {

        @Test
        void theDeadlineIsScheduledWithinTheScopeOfTheSagaOnceTheSagaHasBeenStored() {
            // given
            ReminderService reminderService = new ReminderService(deadlineManager);
            startWith(Sagas.of(CollaboratingSaga.class, () -> new CollaboratingSaga(reminderService)));

            // when
            publish(asEventMessage(new OrderPlaced("order-1")));

            // then
            String sagaId = sagaIdOf(CollaboratingSaga.class);
            assertThat(deadlineManager.scheduled).singleElement()
                                                 .satisfies(deadline -> {
                                                     assertThat(deadline.scope()).isEqualTo(
                                                             new SagaScopeDescriptor("CollaboratingSaga", sagaId)
                                                     );
                                                     assertThat(deadline.sagaStoredWhenScheduled()).isTrue();
                                                 });
        }
    }

    private void startWith(ComponentBuilder<EventHandlingComponent> sagaComponent) {
        configuration = MessagingConfigurer
                .create()
                .componentRegistry(cr -> cr.registerComponent(SagaStore.class, c -> sagaStore)
                                           .registerComponent(DeadlineManager.class, c -> deadlineManager))
                .eventProcessing(processing -> processing.subscribing(
                        subscribing -> subscribing.defaultProcessor(
                                "saga-processor",
                                components -> components.declarative("Saga", sagaComponent))
                ))
                .start();
    }

    private void publish(EventMessage event) {
        FutureUtils.joinAndUnwrap(
                configuration().getComponent(EventSink.class).publish(null, List.of(event)), TIMEOUT
        );
    }

    private AxonConfiguration configuration() {
        return Objects.requireNonNull(configuration, "The configuration has not been started");
    }

    private String sagaIdOf(Class<?> sagaType) {
        Set<String> sagaIds = sagaStore.findSagas(sagaType, ORDER_1);
        assertThat(sagaIds).hasSize(1);
        return sagaIds.iterator().next();
    }

    public record OrderPlaced(String orderId) {

    }

    public record OrderPaid(String orderId) {

    }

    @SuppressWarnings({"unused", "removal"})
    public static class ParameterSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event, DeadlineManager deadlineManager) {
            deadlineManager.schedule(Duration.ofMinutes(5), "paymentReminder");
        }

        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPaid event, DeadlineManager deadlineManager) {
            deadlineManager.cancelAllWithinScope("paymentReminder");
        }
    }

    @SuppressWarnings({"unused", "removal"})
    public static class CollaboratingSaga {

        private final transient ReminderService reminderService;

        public CollaboratingSaga(ReminderService reminderService) {
            this.reminderService = reminderService;
        }

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event) {
            reminderService.remindAboutPayment();
        }
    }

    public record ReminderService(DeadlineManager deadlineManager) {

        void remindAboutPayment() {
            deadlineManager.schedule(Duration.ofMinutes(5), "paymentReminder");
        }
    }

    private record ScheduledDeadline(String deadlineName,
                                     ScopeDescriptor scope,
                                     boolean sagaStoredWhenScheduled,
                                     Map<String, String> metadata) {

    }

    /**
     * Records every deadline it is asked to schedule, together with whether the Saga was already in the store at the
     * moment the deferred call ran, and every cancellation within a scope.
     */
    private static final class RecordingDeadlineManager extends AbstractDeadlineManager {

        private final InMemorySagaStore sagaStore;
        private final List<ScheduledDeadline> scheduled = new CopyOnWriteArrayList<>();
        private final List<String> cancelledWithinScope = new CopyOnWriteArrayList<>();

        private RecordingDeadlineManager(InMemorySagaStore sagaStore) {
            this.sagaStore = sagaStore;
        }

        @Override
        public String schedule(Instant triggerDateTime,
                               String deadlineName,
                               @Nullable Object messageOrPayload,
                               ScopeDescriptor deadlineScope) {
            DeadlineMessage deadlineMessage = asDeadlineMessage(deadlineName, messageOrPayload, triggerDateTime);
            runOnPrepareCommitOrNow(() -> scheduled.add(new ScheduledDeadline(
                    deadlineName, deadlineScope, sagaStore.size() > 0, deadlineMessage.metadata()
            )));
            return deadlineMessage.identifier();
        }

        @Override
        public void cancelSchedule(String deadlineName, String scheduleId) {
            throw new UnsupportedOperationException("Not used by this test");
        }

        @Override
        public void cancelAll(String deadlineName) {
            throw new UnsupportedOperationException("Not used by this test");
        }

        @Override
        public void cancelAllWithinScope(String deadlineName, ScopeDescriptor scope) {
            runOnPrepareCommitOrNow(
                    () -> cancelledWithinScope.add(deadlineName + "@" + scope.scopeDescription())
            );
        }
    }
}
