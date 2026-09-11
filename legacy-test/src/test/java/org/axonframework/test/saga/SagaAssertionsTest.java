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

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValues;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.FixtureExecutionException;
import org.axonframework.test.fixture.AxonTestFixture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaAssertionsTest {

    private final InMemorySagaStore sagaStore = new InMemorySagaStore();

    private @Nullable AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Nested
    class AssociationWith {

        @Test
        void passesWhenASagaHoldsTheAssociation() {
            // given
            store("saga-1", new OrderSaga(), new AssociationValue("orderId", "order-1"));

            // when / then
            assertThatCode(() -> assertOn(SagaAssertions.associationWith(OrderSaga.class, "orderId", "order-1")))
                    .doesNotThrowAnyException();
        }

        @Test
        void failsWhenNoSagaHoldsTheAssociation() {
            // given
            store("saga-1", new OrderSaga(), new AssociationValue("orderId", "order-1"));

            // when / then
            assertThatThrownBy(() -> assertOn(SagaAssertions.associationWith(OrderSaga.class, "orderId", "order-2")))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessage("Expected a saga to be associated with key:<orderId> value:<order-2>, "
                                        + "but found <none>");
        }

        @Test
        void onlyConsidersSagasOfTheGivenType() {
            // given a saga of another type holding the association
            store("saga-1", new ShipmentSaga(), new AssociationValue("orderId", "order-1"));

            // when / then
            assertThatThrownBy(() -> assertOn(SagaAssertions.associationWith(OrderSaga.class, "orderId", "order-1")))
                    .isInstanceOf(AxonAssertionError.class);
        }

        /**
         * Inherited from Axon Framework 4, which passed the association value through {@code toString()} before
         * looking it up.
         */
        @Test
        void aNonStringValueIsComparedByItsStringRepresentation() {
            // given
            store("saga-1", new OrderSaga(), new AssociationValue("orderId", "42"));

            // when / then
            assertThatCode(() -> assertOn(SagaAssertions.associationWith(OrderSaga.class, "orderId", 42)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class NoAssociationWith {

        @Test
        void passesWhenNoSagaHoldsTheAssociation() {
            // given
            store("saga-1", new OrderSaga(), new AssociationValue("orderId", "order-1"));

            // when / then
            assertThatCode(() -> assertOn(SagaAssertions.noAssociationWith(OrderSaga.class, "orderId", "order-2")))
                    .doesNotThrowAnyException();
        }

        @Test
        void failsWhenASagaHoldsTheAssociation() {
            // given
            store("saga-1", new OrderSaga(), new AssociationValue("orderId", "order-1"));

            // when / then
            assertThatThrownBy(() -> assertOn(SagaAssertions.noAssociationWith(OrderSaga.class, "orderId", "order-1")))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessage("Expected no sagas to be associated with key:<orderId> value:<order-1>, "
                                        + "but found <1>");
        }
    }

    @Nested
    class ActiveSagas {

        @Test
        void passesWhenTheStoreHoldsTheExpectedNumber() {
            // given
            store("saga-1", new OrderSaga(), new AssociationValue("orderId", "order-1"));
            store("saga-2", new OrderSaga(), new AssociationValue("orderId", "order-2"));

            // when / then
            assertThatCode(() -> assertOn(SagaAssertions.activeSagas(2))).doesNotThrowAnyException();
        }

        @Test
        void failsWhenTheStoreHoldsAnotherNumber() {
            // given
            store("saga-1", new OrderSaga(), new AssociationValue("orderId", "order-1"));

            // when / then
            assertThatThrownBy(() -> assertOn(SagaAssertions.activeSagas(2)))
                    .isInstanceOf(AxonAssertionError.class)
                    .hasMessage("Wrong number of active sagas.\nExpected <2>,\n but got <1>.");
        }

        /**
         * Inherited from Axon Framework 4, whose {@code InMemorySagaStore#size()} counts every saga it holds. That
         * makes the count asymmetric with the association assertions, which do filter by saga type.
         */
        @Test
        void countsSagasOfEveryTypeInTheStore() {
            // given one saga of each of two types
            store("saga-1", new OrderSaga(), new AssociationValue("orderId", "order-1"));
            store("saga-2", new ShipmentSaga(), new AssociationValue("shipmentId", "shipment-1"));

            // when / then
            assertThatCode(() -> assertOn(SagaAssertions.activeSagas(2))).doesNotThrowAnyException();
        }

        @Test
        void reportsAStoreItCannotCount() {
            // given a store that cannot report how many sagas it holds
            assertThatThrownBy(() -> assertOn(SagaAssertions.activeSagas(1), new CountlessSagaStore()))
                    .isInstanceOf(FixtureExecutionException.class)
                    .hasMessageContaining(CountlessSagaStore.class.getName())
                    .hasMessageContaining(InMemorySagaStore.class.getSimpleName());
        }
    }

    @Nested
    class WithoutASagaStore {

        @Test
        void reportsTheMissingComponent() {
            // given a configuration without a SagaStore
            configuration = MessagingConfigurer.create()
                                               .componentRegistry(ComponentRegistry::disableEnhancerScanning)
                                               .start();

            // when / then
            assertThatThrownBy(() -> SagaAssertions.activeSagas(1).accept(configuration))
                    .isInstanceOf(FixtureExecutionException.class)
                    .hasMessageContaining(SagaStore.class.getName());
        }
    }

    /**
     * The assertions exist to be handed to {@link AxonTestFixture}, so the composition is pinned here rather than left
     * to the Javadoc.
     */
    @Nested
    class ThroughTheFixture {

        @Test
        void assertionsComposeWithTheThenPhase() {
            // given a fixture over a configuration holding the store
            store("saga-1", new OrderSaga(), new AssociationValue("orderId", "order-1"));
            var configurer = MessagingConfigurer.create()
                                                .componentRegistry(ComponentRegistry::disableEnhancerScanning)
                                                .componentRegistry(cr -> cr.registerComponent(SagaStore.class,
                                                                                              c -> sagaStore));
            var fixture = AxonTestFixture.with(configurer);

            // when / then
            fixture.given()
                   .noPriorActivity()
                   .when()
                   .nothing()
                   .then()
                   .expect(SagaAssertions.activeSagas(1))
                   .expect(SagaAssertions.associationWith(OrderSaga.class, "orderId", "order-1"))
                   .expect(SagaAssertions.noAssociationWith(OrderSaga.class, "orderId", "order-2"));

            fixture.stop();
        }
    }

    private void assertOn(java.util.function.Consumer<Configuration> assertion) {
        assertOn(assertion, sagaStore);
    }

    private void assertOn(java.util.function.Consumer<Configuration> assertion, SagaStore<Object> store) {
        configuration = MessagingConfigurer.create()
                                           .componentRegistry(ComponentRegistry::disableEnhancerScanning)
                                           .componentRegistry(cr -> cr.registerComponent(SagaStore.class, c -> store))
                                           .start();
        assertion.accept(configuration);
    }

    private void store(String sagaIdentifier, Object saga, AssociationValue... associationValues) {
        sagaStore.insertSaga(saga.getClass(), sagaIdentifier, saga, Set.of(associationValues));
    }

    private static class OrderSaga {

    }

    private static class ShipmentSaga {

    }

    /**
     * A {@link SagaStore} that is not an {@link InMemorySagaStore}, so it has no way to report a total count.
     */
    private static class CountlessSagaStore implements SagaStore<Object> {

        @Override
        public Set<String> findSagas(Class<?> sagaType, AssociationValue associationValue) {
            return Set.of();
        }

        @Override
        public @Nullable <S> Entry<S> loadSaga(Class<S> sagaType, String sagaIdentifier) {
            return null;
        }

        @Override
        public void deleteSaga(Class<?> sagaType, String sagaIdentifier, Set<AssociationValue> associationValues) {
        }

        @Override
        public void insertSaga(Class<?> sagaType, String sagaIdentifier, Object saga,
                               Set<AssociationValue> associationValues) {
        }

        @Override
        public void updateSaga(Class<?> sagaType, String sagaIdentifier, Object saga,
                               AssociationValues associationValues) {
        }
    }
}
