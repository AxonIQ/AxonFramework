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

import org.axonframework.messaging.core.annotation.AggregateType;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.SourceId;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.SequenceNumber;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaLifecycle;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.test.AxonAssertionError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the Axon Framework 4 given-when-then flow of {@link SagaTestFixture} over the Axon Framework 5 fixture
 * underneath it.
 *
 * @author Mateusz Nowak
 */
class SagaTestFixtureGivenWhenTest {

    private final SagaTestFixture<OrderSaga> fixture = new SagaTestFixture<>(OrderSaga.class);

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Nested
    class GivenPhase {

        @Test
        void givenNoPriorActivityLeavesTheStoreEmpty() {
            fixture.givenNoPriorActivity()
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectActiveSagas(0);
        }

        @Test
        void givenAPublishedCreatesTheSaga() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectActiveSagas(1)
                   .expectAssociationWith("orderId", "order-1");
        }

        @Test
        void severalGivenEventsAreChained() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .andThenAPublished(new OrderPlaced("order-2"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectActiveSagas(2)
                   .expectAssociationWith("orderId", "order-1")
                   .expectAssociationWith("orderId", "order-2");
        }

        @Test
        void metadataOnAGivenEventReachesTheSaga() {
            fixture.givenAPublished(new OrderPlaced("order-1"), Map.of("channel", "web"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectAssociationWith("channel", "web");
        }
    }

    @Nested
    class WhenPhase {

        @Test
        void whenPublishingAReachesTheSaga() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderCompleted("shipment-of-order-1"))
                   .expectActiveSagas(0)
                   .expectNoAssociationWith("orderId", "order-1");
        }

        @Test
        void metadataOnTheWhenEventReachesTheSaga() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"), Map.of("channel", "mobile"))
                   .expectAssociationWith("channel", "mobile");
        }
    }

    @Nested
    class AggregatePublishers {

        @Test
        void givenAggregatePublishesEventsIntoTheGivenPhase() {
            fixture.givenAggregate("order-1").published(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectActiveSagas(1);
        }

        @Test
        void andThenAggregateAppendsToTheGivenPhase() {
            fixture.givenAggregate("order-1").published(new OrderPlaced("order-1"))
                   .andThenAggregate("order-2").published(new OrderPlaced("order-2"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectActiveSagas(2);
        }

        @Test
        void whenAggregatePublishesIntoTheWhenPhase() {
            fixture.givenAggregate("order-1").published(new OrderPlaced("order-1"))
                   .whenAggregate("order-1").publishes(new OrderCompleted("shipment-of-order-1"))
                   .expectActiveSagas(0);
        }

        /**
         * Axon Framework 4 published these on a {@code DomainEventMessage}. The three fields it carried reach a Saga
         * handler as parameters here, with the same synthesized aggregate type and the same sequence numbering.
         */
        @Test
        void theAggregateEnvelopeReachesTheSagaAsHandlerParameters() {
            fixture.givenAggregate("order-1").published(new OrderPlaced("order-1"),
                                                        new OrderNoted("shipment-of-order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectAssociationWith("aggregate", "Stub_order-1/order-1/1");
        }

        @Test
        void sequenceNumbersCountUpPerAggregateAcrossTheGivenAndWhenPhases() {
            fixture.givenAggregate("order-1").published(new OrderPlaced("order-1"),
                                                        new OrderNoted("shipment-of-order-1"))
                   .whenAggregate("order-1").publishes(new OrderNoted("shipment-of-order-1"))
                   .expectAssociationWith("sequenceNumber", 2);
        }

        @Test
        void aSecondAggregateStartsCountingAtZero() {
            fixture.givenAggregate("order-1").published(new OrderPlaced("order-1"))
                   .andThenAggregate("order-2").published(new OrderNoted("shipment-of-order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectAssociationWith("aggregate", "Stub_order-2/order-2/0");
        }

        /**
         * The envelope travels as metadata, which the interceptor removes again so a Saga reading its own metadata is
         * unaffected.
         */
        @Test
        void theEnvelopeIsNotVisibleAsMetadata() {
            fixture.givenAggregate("order-1").published(new OrderPlaced("order-1"))
                   .whenAggregate("order-1").publishes(new OrderShipped("shipment-of-order-1"))
                   .expectAssociationWith("metadataKeys", 0);
        }

        /**
         * A handler parameter reading the envelope only resolves when the envelope is there, so a Saga declaring one
         * is not invoked for an event published without an aggregate.
         */
        @Test
        void aHandlerReadingTheEnvelopeIsNotInvokedForAnEventWithoutOne() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderNoted("shipment-of-order-1"))
                   .expectNoAssociationWith("aggregate", "Stub_order-1/order-1/0")
                   .expectActiveSagas(1);
        }

        @Test
        void severalEventsCanBePublishedAtOnce() {
            fixture.givenAggregate("order-1").published(new OrderPlaced("order-1"),
                                                        new OrderPlaced("order-2"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                   .expectActiveSagas(2);
        }
    }

    @Nested
    class FailingAssertions {

        @Test
        void aWrongSagaCountIsReported() {
            assertThatThrownBy(() -> fixture.givenNoPriorActivity()
                                            .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                                            .expectActiveSagas(1))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessageContaining("Wrong number of active sagas");
        }

        @Test
        void aMissingAssociationIsReported() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderPlaced("order-1"))
                                            .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                                            .expectAssociationWith("orderId", "order-2"))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessageContaining("Expected a saga to be associated with");
        }

        @Test
        void anUnexpectedAssociationIsReported() {
            assertThatThrownBy(() -> fixture.givenAPublished(new OrderPlaced("order-1"))
                                            .whenPublishingA(new OrderShipped("shipment-of-order-1"))
                                            .expectNoAssociationWith("orderId", "order-1"))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessageContaining("Expected no sagas to be associated with");
        }

        /**
         * Inherited from Axon Framework 4, which passed the association value through {@code toString()} before
         * looking it up.
         */
        @Test
        void anAssociationValueIsMatchedByItsStringRepresentation() {
            assertThatCode(() -> fixture.givenAPublished(new OrderPlaced("42"))
                                        .whenPublishingA(new OrderShipped("shipment-of-42"))
                                        .expectAssociationWith("orderId", 42))
                    .doesNotThrowAnyException();
        }
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

    public record OrderNoted(String shipmentId) {

    }

    @SuppressWarnings({"unused", "removal"})
    public static class OrderSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event,
                       SagaLifecycle lifecycle,
                       @MetadataValue("channel") String channel) {
            lifecycle.associateWith("shipmentId", shipmentIdFor(event.orderId()));
            if (channel != null) {
                lifecycle.associateWith("channel", channel);
            }
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderNoted event,
                       SagaLifecycle lifecycle,
                       EventMessage message,
                       @AggregateType String aggregateType,
                       @SourceId String aggregateIdentifier,
                       @SequenceNumber Long sequenceNumber) {
            lifecycle.associateWith("aggregate",
                                    aggregateType + "/" + aggregateIdentifier + "/" + sequenceNumber);
            lifecycle.associateWith("sequenceNumber", sequenceNumber);
            lifecycle.associateWith("metadataKeys", message.metadata().size());
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderShipped event,
                       SagaLifecycle lifecycle,
                       EventMessage message,
                       @MetadataValue("channel") String channel) {
            if (channel != null) {
                lifecycle.associateWith("channel", channel);
            }
            lifecycle.associateWith("metadataKeys", message.metadata().size());
        }

        @EndSaga
        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderCompleted event) {
            // Ending the saga is the whole point here.
        }
    }
}
