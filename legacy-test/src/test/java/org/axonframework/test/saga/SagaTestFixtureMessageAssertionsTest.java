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

import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.test.AxonAssertionError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.test.matchers.Matchers.listWithAllOf;
import static org.axonframework.test.matchers.Matchers.messageWithPayload;
import static org.axonframework.test.matchers.Matchers.noEvents;
import static org.hamcrest.CoreMatchers.any;

/**
 * Covers the message assertions of {@link FixtureExecutionResult}: the commands a Saga dispatched, the events it
 * published, and whether it handled the "when" event without failing.
 *
 * @author Mateusz Nowak
 */
class SagaTestFixtureMessageAssertionsTest {

    private final SagaTestFixture<OrderSaga> fixture = new SagaTestFixture<>(OrderSaga.class);

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Nested
    class DispatchedCommands {

        @Test
        void theCommandTheSagaDispatchedIsAsserted() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectDispatchedCommands(new ConfirmOrder("order-1"));
        }

        @Test
        void aSagaThatDispatchedNothingHasNoCommands() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderNoted("shipment-of-order-1"))
                   .expectNoDispatchedCommands();
        }

        @Test
        void theCommandsAreMatchable() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectDispatchedCommandsMatching(
                           listWithAllOf(messageWithPayload(any(ConfirmOrder.class)))
                   );
        }

        @Test
        void theGivenPhaseDispatchIsNotCounted() {
            // The saga already dispatched during the given-phase; only the when-phase is asserted on.
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .andThenAPublished(new OrderShipped("shipment-of-order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectDispatchedCommands(new ConfirmOrder("order-1"));
        }

        @Test
        void aWrongCommandIsReportedWithTheAxonFramework4Message() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderPlaced("order-1"))
                                            .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                                            .expectDispatchedCommands(new ConfirmOrder("order-2")))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessageContaining("Unexpected command at index 0");
        }

        @Test
        void aWrongNumberOfCommandsIsReportedWithTheAxonFramework4Message() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderPlaced("order-1"))
                                            .whenPublishingA(new OrderNoted("shipment-of-order-1"))
                                            .expectDispatchedCommands(new ConfirmOrder("order-1")))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessageContaining("Got wrong number of commands dispatched.");
        }

        @Test
        void anUnexpectedCommandIsReported() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderPlaced("order-1"))
                                            .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                                            .expectNoDispatchedCommands())
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessageContaining("Incorrect dispatched command.");
        }
    }

    @Nested
    class PublishedEvents {

        @Test
        void theEventTheSagaPublishedIsAsserted() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderNoted("shipment-of-order-1"))
                   .expectPublishedEvents(new OrderRecorded("order-1"));
        }

        /**
         * The event that drove the "when" phase travelled through the same recording sink as the Saga's own output.
         * Axon Framework 4 never saw it, because it fed the Saga directly, and neither does this.
         */
        @Test
        void theWhenEventIsNotCountedAsSagaOutput() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectPublishedEvents();
        }

        @Test
        void anExplicitWhenEventMessageIsNotCountedAsSagaOutput() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new GenericEventMessage(
                           new MessageType(OrderShipped.class),
                           new OrderShipped("shipment-of-order-1")
                   ))
                   .expectPublishedEvents();
        }

        @Test
        void eachWhenPublishingAStartsAFreshRecording() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderNoted("shipment-of-order-1"))
                   .expectPublishedEvents(new OrderRecorded("order-1"));

            fixture.whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectPublishedEvents();
        }

        @Test
        void theEventsAreMatchable() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderNoted("shipment-of-order-1"))
                   .expectPublishedEventsMatching(
                           listWithAllOf(messageWithPayload(any(OrderRecorded.class)))
                   );
        }

        @Test
        void aWrongNumberOfEventsIsReportedWithTheAxonFramework4Message() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderPlaced("order-1"))
                                            .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                                            .expectPublishedEvents(new OrderRecorded("order-1")))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessageContaining("Got wrong number of events published.");
        }

        @Test
        void aWrongEventIsReportedWithTheAxonFramework4Message() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderPlaced("order-1"))
                                            .whenPublishingA(new OrderNoted("shipment-of-order-1"))
                                            .expectPublishedEvents(new OrderRecorded("order-2")))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessageContaining("Published events did not match.");
        }

        @Test
        void anUnexpectedEventIsReported() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderPlaced("order-1"))
                                            .whenPublishingA(new OrderNoted("shipment-of-order-1"))
                                            .expectPublishedEventsMatching(noEvents()))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessageContaining("Published events did not match.");
        }
    }

    @Nested
    class HandlerExecution {

        @Test
        void aSagaThatHandledTheEventWithoutFailingIsSuccessful() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectSuccessfulHandlerExecution();
        }

        @Test
        void aFailingSagaIsReported() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderPlaced("order-1"))
                                            .whenPublishingA(new OrderCancelled("shipment-of-order-1"))
                                            .expectSuccessfulHandlerExecution())
                    .isInstanceOf(AxonAssertionError.class);
        }

        /**
         * An {@code @ExceptionHandler} on the Saga is what Axon Framework 4's {@code ListenerInvocationErrorHandler}
         * default became: the failure is swallowed, the unit of work commits, and the Saga counts as successful.
         */
        @Test
        void aSagaSuppressingItsOwnFailureIsSuccessful() {
            SagaTestFixture<SuppressingSaga> suppressing = new SagaTestFixture<>(SuppressingSaga.class);
            try {
                suppressing.givenAPublished(new OrderPlaced("order-1"))
                           .whenPublishingA(new OrderCancelled("shipment-of-order-1"))
                           .expectSuccessfulHandlerExecution()
                           .expectActiveSagas(1);
            } finally {
                suppressing.stop();
            }
        }
    }

    private static String shipmentIdFor(String orderId) {
        return "shipment-of-" + orderId;
    }

    public record OrderPlaced(String orderId) {

    }

    public record OrderShipped(String shipmentId) {

    }

    public record OrderNoted(String shipmentId) {

    }

    public record OrderCancelled(String shipmentId) {

    }

    public record OrderRecorded(String orderId) {

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

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderShipped event, CommandDispatcher commands) {
            commands.send(new ConfirmOrder(orderId));
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderNoted event, EventAppender events) {
            events.append(new OrderRecorded(orderId));
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderCancelled event) {
            throw new IllegalStateException("cannot cancel a shipped order");
        }
    }

    @SuppressWarnings({"unused", "removal"})
    public static class SuppressingSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event, SagaLifecycle lifecycle) {
            lifecycle.associateWith("shipmentId", shipmentIdFor(event.orderId()));
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderCancelled event) {
            throw new IllegalStateException("cannot cancel a shipped order");
        }

        @ExceptionHandler
        public void on(IllegalStateException failure) {
            // Swallowed, as the Axon Framework 4 default did.
        }
    }
}
