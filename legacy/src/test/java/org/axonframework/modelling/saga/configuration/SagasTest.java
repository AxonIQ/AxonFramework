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

package org.axonframework.modelling.saga.configuration;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.EventHandlingExceptionHandler;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shows that a Saga registered through {@link Sagas} on an event processor handles published events and is
 * stored, which is the assembly a user would otherwise write out by hand.
 *
 * @author Mateusz Nowak
 */
class SagasTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");

    private final InMemorySagaStore sagaStore = new InMemorySagaStore();

    private @Nullable AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Nested
    class BehindASubscribingProcessor {

        @Test
        void aStartingEventCreatesAndStoresTheSaga() {
            // given
            startWith(Sagas.of(OrderSaga.class));

            // when
            publish(orderPlaced("order-1"));

            // then
            assertThat(sagaStore.findSagas(OrderSaga.class, ORDER_1)).hasSize(1);
        }

        @Test
        void aFollowUpEventReachesTheSagaThatAssociatedItself() {
            // given a saga that associated itself with the shipment on creation
            startWith(Sagas.of(OrderSaga.class));
            publish(orderPlaced("order-1"));

            // when
            publish(orderShipped("shipment-of-order-1"));

            // then
            assertThat(sagaOf(ORDER_1).shippedCount).isEqualTo(1);
        }

        @Test
        void anEndingEventRemovesTheSagaFromTheStore() {
            // given
            startWith(Sagas.of(OrderSaga.class));
            publish(orderPlaced("order-1"));

            // when
            publish(orderCompleted("shipment-of-order-1"));

            // then
            assertThat(sagaStore.findSagas(OrderSaga.class, ORDER_1)).isEmpty();
        }

        @Test
        void anEventTheSagaDoesNotHandleCreatesNothing() {
            // given
            startWith(Sagas.of(OrderSaga.class));

            // when
            publish(new GenericEventMessage(new MessageType(Unrelated.class), new Unrelated("order-1")));

            // then
            assertThat(sagaStore.size()).isZero();
        }
    }

    @Nested
    class SagaFactory {

        @Test
        void theGivenFactoryConstructsTheSaga() {
            // given a saga needing a collaborator its handler cannot receive
            Collaborator collaborator = new Collaborator();
            startWith(Sagas.of(CollaboratingSaga.class, () -> new CollaboratingSaga(collaborator)));

            // when
            publish(orderPlaced("order-1"));

            // then
            SagaStore.Entry<CollaboratingSaga> entry = sagaStore.loadSaga(
                    CollaboratingSaga.class,
                    sagaStore.findSagas(CollaboratingSaga.class, ORDER_1).iterator().next()
            );
            assertThat(entry).isNotNull();
            assertThat(entry.saga().collaborator).isSameAs(collaborator);
        }
    }

    @Nested
    class ExplicitSagaStore {

        @Test
        void theGivenStoreIsUsedInsteadOfTheRegisteredComponent() {
            // given a second store, handed to the component builder directly
            InMemorySagaStore otherStore = new InMemorySagaStore();
            startWith(Sagas.of(OrderSaga.class, OrderSaga::new, c -> otherStore));

            // when
            publish(orderPlaced("order-1"));

            // then
            assertThat(otherStore.findSagas(OrderSaga.class, ORDER_1)).hasSize(1);
            assertThat(sagaStore.size()).isZero();
        }
    }

    /**
     * The result is an ordinary component builder rather than a whole event handling phase, which is what keeps a
     * processor able to carry more than one Saga and to decorate the lot.
     */
    @Nested
    class ComposedWithOtherComponents {

        @Test
        void oneProcessorCarriesTwoSagas() {
            // given both Saga types on the same processor
            configuration = MessagingConfigurer
                    .create()
                    .componentRegistry(cr -> cr.registerComponent(SagaStore.class, c -> sagaStore))
                    .eventProcessing(processing -> processing.subscribing(
                            subscribing -> subscribing.defaultProcessor(
                                    "sagas",
                                    components -> components
                                            .declarative("Saga[OrderSaga]", Sagas.of(OrderSaga.class))
                                            .declarative("Saga[ShipmentSaga]", Sagas.of(ShipmentSaga.class)))
                    ))
                    .start();

            // when a single event starts both
            publish(orderPlaced("order-1"));

            // then
            assertThat(sagaStore.findSagas(OrderSaga.class, ORDER_1)).hasSize(1);
            assertThat(sagaStore.findSagas(ShipmentSaga.class, ORDER_1)).hasSize(1);
        }

        /**
         * Processor-level exception handling is not the Axon Framework 4 {@code ListenerInvocationErrorHandler}: it
         * wraps the whole Saga manager, so by the time it runs the manager has already abandoned the remaining Sagas
         * and skipped Saga creation. An {@code @ExceptionHandler} on the Saga catches the failure per instance and
         * leaves both intact, which is the behaviour Axon Framework 4 had, and is pinned by
         * {@code AnnotatedSagaManagerTest.SuppressedHandlerFailures}. What this test covers is only that the
         * decoration is reachable at all.
         */
        @Test
        void anExceptionHandlerRegisteredOnTheProcessorSeesTheSagaFailure() {
            // given a Saga whose handler fails, behind an exception handler that swallows it
            List<Throwable> handled = new CopyOnWriteArrayList<>();
            configuration = MessagingConfigurer
                    .create()
                    .componentRegistry(cr -> cr.registerComponent(SagaStore.class, c -> sagaStore))
                    .eventProcessing(processing -> processing.subscribing(
                            subscribing -> subscribing.defaultProcessor(
                                    "sagas",
                                    components -> components
                                            .declarative("Saga[FailingSaga]", Sagas.of(FailingSaga.class))
                                            .withExceptionHandler(c -> (EventHandlingExceptionHandler) (
                                                    event, context, error) -> {
                                                handled.add(error);
                                                return MessageStream.empty();
                                            }))
                    ))
                    .start();

            // when
            publish(orderPlaced("order-1"));

            // then the failure reached the handler instead of the publisher
            assertThat(handled).hasSize(1);
            assertThat(handled.getFirst()).hasMessage("this saga always fails");
        }
    }

    @Nested
    class WithoutASagaStore {

        @Test
        void startingTheConfigurationReportsTheMissingComponent() {
            // given a configuration registering the saga but no store
            MessagingConfigurer configurer =
                    MessagingConfigurer.create()
                                       .eventProcessing(processing -> processing.subscribing(
                                               subscribing -> subscribing.defaultProcessor(
                                                       "OrderSaga",
                                                       components -> components.declarative(
                                                               "Saga[OrderSaga]",
                                                               Sagas.of(OrderSaga.class)))));

            // when / then the processor cannot be started, and says which component is missing and for which saga
            assertThatThrownBy(() -> configuration = configurer.start())
                    .rootCause()
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining(SagaStore.class.getName())
                    .hasMessageContaining(OrderSaga.class.getName());
        }
    }

    private void startWith(ComponentBuilder<EventHandlingComponent> sagaComponent) {
        configuration = MessagingConfigurer
                .create()
                .componentRegistry(cr -> cr.registerComponent(SagaStore.class, c -> sagaStore))
                .eventProcessing(processing -> processing.subscribing(
                        subscribing -> subscribing.defaultProcessor(
                                "saga-processor",
                                components -> components.declarative("Saga", sagaComponent))
                ))
                .start();
    }

    private void publish(EventMessage event) {
        FutureUtils.joinAndUnwrap(
                configuration.getComponent(EventSink.class).publish(null, List.of(event)), TIMEOUT
        );
    }

    private OrderSaga sagaOf(AssociationValue associationValue) {
        String sagaId = sagaStore.findSagas(OrderSaga.class, associationValue).iterator().next();
        SagaStore.Entry<OrderSaga> entry = sagaStore.loadSaga(OrderSaga.class, sagaId);
        assertThat(entry).isNotNull();
        return entry.saga();
    }

    private static EventMessage orderPlaced(String orderId) {
        return new GenericEventMessage(new MessageType(OrderPlaced.class), new OrderPlaced(orderId));
    }

    private static EventMessage orderShipped(String shipmentId) {
        return new GenericEventMessage(new MessageType(OrderShipped.class), new OrderShipped(shipmentId));
    }

    private static EventMessage orderCompleted(String shipmentId) {
        return new GenericEventMessage(new MessageType(OrderCompleted.class), new OrderCompleted(shipmentId));
    }

    private static String shipmentIdFor(String orderId) {
        return "shipment-of-" + orderId;
    }

    public record OrderPlaced(String orderId) {

    }

    public record OrderShipped(String shipmentId) {

    }

    public record OrderCompleted(String shipmentId) {

    }

    public record Unrelated(String orderId) {

    }

    @SuppressWarnings({"unused", "removal"})
    public static class OrderSaga {

        private int shippedCount = 0;

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event, SagaLifecycle lifecycle) {
            lifecycle.associateWith("shipmentId", shipmentIdFor(event.orderId()));
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderShipped event) {
            shippedCount++;
        }

        @EndSaga
        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderCompleted event) {
            // Ending the saga is the whole point here.
        }
    }

    @SuppressWarnings({"unused", "removal"})
    public static class ShipmentSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event) {
            // A second saga type reacting to the same event.
        }
    }

    @SuppressWarnings({"unused", "removal"})
    public static class FailingSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event) {
            throw new IllegalStateException("this saga always fails");
        }
    }

    private static class Collaborator {

    }

    @SuppressWarnings({"unused", "removal"})
    public static class CollaboratingSaga {

        private final Collaborator collaborator;

        CollaboratingSaga(Collaborator collaborator) {
            this.collaborator = collaborator;
        }

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event) {
            // Starting the saga is enough; the collaborator is what the test looks at.
        }
    }
}
