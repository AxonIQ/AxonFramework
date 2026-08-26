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

import org.axonframework.deadline.annotation.DeadlineHandler;
import org.axonframework.messaging.core.ScopeAware;
import org.axonframework.messaging.core.ScopeAwareProvider;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.ResultMessage;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.modelling.saga.AnnotatedSagaManager;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaScopeDescriptor;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Validates that a deadline scheduled for a saga's scope is delivered back to that saga's
 * {@link DeadlineHandler @DeadlineHandler} method.
 * <p>
 * This covers the full round trip a saga deadline makes: a {@link SimpleDeadlineManager} schedules against a
 * {@link SagaScopeDescriptor}, and on expiry resolves the owning {@link ScopeAware} component through a
 * {@link ScopeAwareProvider} so the {@link DeadlineMessage} reaches the saga instance that scheduled it.
 *
 * @author Axon Framework
 */
class SagaDeadlineDeliveryTest {

    private static final String SAGA_ID = "saga-id";

    private InMemorySagaStore sagaStore;
    private AnnotatedSagaManager<DeadlineSaga> sagaManager;
    private SimpleDeadlineManager deadlineManager;

    @BeforeEach
    void setUp() {
        DeadlineSaga.reset();
        sagaStore = new InMemorySagaStore();
        AnnotatedSagaRepository<DeadlineSaga> sagaRepository =
                AnnotatedSagaRepository.<DeadlineSaga>builder()
                                       .sagaType(DeadlineSaga.class)
                                       .sagaStore(sagaStore)
                                       .build();
        sagaManager = AnnotatedSagaManager.<DeadlineSaga>builder()
                                          .sagaRepository(sagaRepository)
                                          .sagaType(DeadlineSaga.class)
                                          .sagaFactory(DeadlineSaga::new)
                                          .build();
        deadlineManager = SimpleDeadlineManager.builder()
                                               .scopeAwareProvider(new SagaManagerScopeAwareProvider(sagaManager))
                                               .build();
    }

    @AfterEach
    void tearDown() {
        deadlineManager.shutdown();
    }

    @Test
    void deadlineScheduledForASagaScopeReachesThatSagasDeadlineHandler() throws Exception {
        // given a live saga
        startSaga();
        String sagaIdentifier = onlySagaIdentifier();

        // when a deadline is scheduled against that saga's scope and expires
        deadlineManager.schedule(Duration.ofMillis(50),
                                 "sagaDeadline",
                                 "deadline-payload",
                                 new SagaScopeDescriptor(DeadlineSaga.class.getSimpleName(), sagaIdentifier));

        // then the saga's deadline handler is invoked with that payload
        assertThat(DeadlineSaga.HANDLED.await(5, TimeUnit.SECONDS))
                .withFailMessage("The saga's @DeadlineHandler was never invoked")
                .isTrue();
        assertThat(DeadlineSaga.PAYLOADS).containsExactly("deadline-payload");
    }

    @Test
    void cancelledDeadlineNeverReachesTheSaga() throws Exception {
        // given a live saga with a scheduled deadline
        startSaga();
        String sagaIdentifier = onlySagaIdentifier();
        SagaScopeDescriptor scope = new SagaScopeDescriptor(DeadlineSaga.class.getSimpleName(), sagaIdentifier);
        String scheduleId = deadlineManager.schedule(Duration.ofMillis(500), "sagaDeadline", "cancelled", scope);

        // when the schedule is cancelled before it expires
        deadlineManager.cancelSchedule("sagaDeadline", scheduleId);

        // then the handler is never invoked
        assertThat(DeadlineSaga.HANDLED.await(1, TimeUnit.SECONDS))
                .withFailMessage("The @DeadlineHandler ran for a cancelled deadline")
                .isFalse();
        assertThat(DeadlineSaga.PAYLOADS).isEmpty();
    }

    private void startSaga() throws Exception {
        EventMessage startEvent = asEventMessage(new SagaStartingEvent(SAGA_ID));
        ResultMessage result = LegacyDefaultUnitOfWork.startAndGet(startEvent).executeWithResult(context -> {
            sagaManager.handle(startEvent, context, Segment.ROOT_SEGMENT);
            return null;
        });
        if (result != null && result.payload() instanceof Exception e) {
            throw e;
        }
    }

    private String onlySagaIdentifier() {
        var identifiers = sagaStore.findSagas(DeadlineSaga.class, new AssociationValue("id", SAGA_ID));
        assertThat(identifiers).hasSize(1);
        return identifiers.iterator().next();
    }

    /**
     * Resolves the single {@link AnnotatedSagaManager} under test, mirroring what the configuration-backed provider
     * does in a running application.
     */
    private record SagaManagerScopeAwareProvider(ScopeAware sagaManager) implements ScopeAwareProvider {

        @Override
        public Stream<ScopeAware> provideScopeAwareStream(ScopeDescriptor scopeDescriptor) {
            return Stream.of(sagaManager);
        }
    }

    @SuppressWarnings("unused")
    private static class DeadlineSaga {

        private static CountDownLatch HANDLED = new CountDownLatch(1);
        private static final List<String> PAYLOADS = new CopyOnWriteArrayList<>();

        static void reset() {
            HANDLED = new CountDownLatch(1);
            PAYLOADS.clear();
        }

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        public void on(SagaStartingEvent event) {
            // Keeps the saga alive so a deadline can be delivered to it.
        }

        @DeadlineHandler(deadlineName = "sagaDeadline")
        public void handle(String payload) {
            PAYLOADS.add(payload);
            HANDLED.countDown();
        }
    }

    private record SagaStartingEvent(String id) {

    }
}
