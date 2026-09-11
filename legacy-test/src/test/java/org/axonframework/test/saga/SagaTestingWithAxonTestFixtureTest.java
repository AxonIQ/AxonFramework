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

package org.axonframework.test.saga;

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.configuration.Sagas;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.test.fixture.AxonTestFixture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Shows a Saga carried by {@code axon-legacy} driven through the Axon Framework 5 {@link AxonTestFixture}, with
 * {@link SagaAssertions} covering what the fixture cannot see by itself.
 * <p>
 * A subscribing event processor handles on the publishing thread, so every assertion here holds without
 * {@link org.axonframework.test.fixture.AxonTestPhase.Then.Message#await(java.util.function.Consumer) await}.
 *
 * @author Mateusz Nowak
 */
class SagaTestingWithAxonTestFixtureTest {

    private @Nullable AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        MessagingConfigurer configurer =
                MessagingConfigurer.create()
                                   .componentRegistry(cr -> cr.registerComponent(SagaStore.class,
                                                                                 c -> new InMemorySagaStore()))
                                   .componentRegistry(cr -> cr.registerDecorator(
                                           CommandBus.class,
                                           0,
                                           (c, name, delegate) -> delegate.subscribe(
                                                   new QualifiedName(ConfirmOrder.class),
                                                   (command, context) -> MessageStream.empty().cast())
                                   ))
                                   .eventProcessing(processing -> processing.subscribing(
                                           subscribing -> subscribing.defaultProcessor(
                                                   "OrderSaga",
                                                   components -> components.declarative(
                                                           "Saga[OrderSaga]",
                                                           Sagas.of(OrderSaga.class)))
                                   ));

        fixture = AxonTestFixture.with(configurer);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.stop();
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void aStartingEventCreatesTheSagaAndItsAssociations() {
            fixture.given()
                   .noPriorActivity()
                   .when()
                   .event(new OrderPlaced("order-1"))
                   .then()
                   .success()
                   .expect(SagaAssertions.activeSagas(1))
                   .expect(SagaAssertions.associationWith(OrderSaga.class, "orderId", "order-1"))
                   .expect(SagaAssertions.associationWith(OrderSaga.class, "shipmentId", "shipment-of-order-1"));
        }

        @Test
        void anEndingEventRemovesTheSaga() {
            fixture.given()
                   .event(new OrderPlaced("order-1"))
                   .when()
                   .event(new OrderCompleted("shipment-of-order-1"))
                   .then()
                   .success()
                   .expect(SagaAssertions.activeSagas(0))
                   .expect(SagaAssertions.noAssociationWith(OrderSaga.class, "orderId", "order-1"));
        }

        @Test
        void anEventNoSagaIsAssociatedWithStartsNothing() {
            fixture.given()
                   .noPriorActivity()
                   .when()
                   .event(new OrderShipped("shipment-of-order-1"))
                   .then()
                   .success()
                   .expect(SagaAssertions.activeSagas(0))
                   .noCommands();
        }
    }

    @Nested
    class DispatchedCommands {

        @Test
        void aFollowUpEventReachesTheSagaWhichDispatchesACommand() {
            fixture.given()
                   .event(new OrderPlaced("order-1"))
                   .when()
                   .event(new OrderShipped("shipment-of-order-1"))
                   .then()
                   .success()
                   .commands(new ConfirmOrder("order-1"));
        }

        @Test
        void theGivenPhaseIsNotAssertedOn() {
            // The given-phase already made the saga dispatch, but entering the when-phase resets the recordings.
            fixture.given()
                   .events(new OrderPlaced("order-1"), new OrderShipped("shipment-of-order-1"))
                   .when()
                   .event(new OrderShipped("shipment-of-order-1"))
                   .then()
                   .commands(new ConfirmOrder("order-1"));
        }
    }

    @Nested
    class PublishedEvents {

        @Test
        void theWhenEventIsNotAssertedAsOutput() {
            OrderShipped whenEvent = new OrderShipped("shipment-of-order-1");
            fixture.given()
                   .event(new OrderPlaced("order-1"))
                   .when()
                   .event(whenEvent)
                   .then()
                   .noEvents();
        }
    }

    @Nested
    class FailingHandler {

        @Test
        void aFailureInTheSagaSurfacesAsTheWhenPhaseException() {
            fixture.given()
                   .event(new OrderPlaced("order-1"))
                   .when()
                   .event(new OrderCancelled("shipment-of-order-1"))
                   .then()
                   .exception(IllegalStateException.class, "cannot cancel a shipped order");
        }

        @Test
        void aFailingSagaIsNotWritten() {
            fixture.given()
                   .noPriorActivity()
                   .when()
                   .event(new OrderPlacedThatFails("order-1"))
                   .then()
                   .exception(IllegalStateException.class)
                   .expect(SagaAssertions.activeSagas(0));
        }
    }

    private static String shipmentIdFor(String orderId) {
        return "shipment-of-" + orderId;
    }

    public record OrderPlaced(String orderId) {

    }

    public record OrderPlacedThatFails(String orderId) {

    }

    public record OrderShipped(String shipmentId) {

    }

    public record OrderCompleted(String shipmentId) {

    }

    public record OrderCancelled(String shipmentId) {

    }

    public record ConfirmOrder(String orderId) {

    }

    @SuppressWarnings({"unused", "removal"})
    public static class OrderSaga {

        private String orderId;

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event, SagaLifecycle lifecycle) {
            this.orderId = event.orderId();
            lifecycle.associateWith("shipmentId", shipmentIdFor(event.orderId()));
        }

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlacedThatFails event) {
            throw new IllegalStateException("cannot start this order");
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderShipped event, CommandDispatcher commands) {
            commands.send(new ConfirmOrder(orderId));
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderCancelled event) {
            throw new IllegalStateException("cannot cancel a shipped order");
        }

        @EndSaga
        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderCompleted event) {
            // Ending the saga is the whole point here.
        }
    }
}
