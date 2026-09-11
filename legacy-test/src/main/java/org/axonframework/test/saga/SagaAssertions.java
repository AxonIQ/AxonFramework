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

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.FixtureExecutionException;
import org.axonframework.test.fixture.AxonTestFixture;
import org.axonframework.test.fixture.AxonTestPhase;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.String.format;

/**
 * Assertions on the contents of the {@link SagaStore} of a running {@link Configuration}.
 * <p>
 * A Saga keeps its state in a {@code SagaStore} rather than in messages, so a test that only observes published events
 * and dispatched commands cannot tell a Saga that ended from one that never started. These assertions close that gap.
 * They are the one thing {@link AxonTestFixture} cannot express on its own, and are meant to be handed to its
 * {@link AxonTestPhase.Then.MessageAssertions#expect(Consumer) expect} operation:
 * <pre>{@code
 * fixture.given()
 *        .event(new OrderPlaced("order-1"))
 *        .when()
 *        .event(new OrderShipped("shipment-of-order-1"))
 *        .then()
 *        .commands(new ConfirmOrder("order-1"))
 *        .expect(SagaAssertions.activeSagas(1))
 *        .expect(SagaAssertions.associationWith(OrderSaga.class, "orderId", "order-1"));
 * }</pre>
 * The {@code SagaStore} is resolved from the {@code Configuration} as a component, so the configuration under test has
 * to register one. {@link SagaTestFixture} does that itself and offers the same assertions under their Axon
 * Framework 4 names.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Internal
public final class SagaAssertions {

    private SagaAssertions() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Asserts that the {@link SagaStore} holds {@code expected} Sagas.
     * <p>
     * Counts every Saga in the store, whatever its type, which is what Axon Framework 4 did and is why this assertion
     * takes no Saga type while {@link #associationWith(Class, String, Object)} does. The count is only available from
     * an {@link InMemorySagaStore}; against any other store this throws a {@link FixtureExecutionException}.
     *
     * @param expected the number of Sagas the store is expected to hold
     * @return an assertion to hand to {@link AxonTestPhase.Then.MessageAssertions#expect(Consumer)}
     */
    public static Consumer<Configuration> activeSagas(int expected) {
        return configuration -> assertActiveSagas(sagaStoreOf(configuration), expected);
    }

    /**
     * Asserts that a Saga of the given {@code sagaType} is associated with the given {@code key} and {@code value}.
     * <p>
     * The {@code value} is compared by its {@link Object#toString() string representation}, as Axon Framework 4 did,
     * because that is the form an {@link AssociationValue} holds.
     *
     * @param sagaType the type of Saga to look for
     * @param key      the key of the association
     * @param value    the value of the association, compared by its string representation
     * @return an assertion to hand to {@link AxonTestPhase.Then.MessageAssertions#expect(Consumer)}
     */
    public static Consumer<Configuration> associationWith(Class<?> sagaType, String key, Object value) {
        Objects.requireNonNull(sagaType, "The sagaType may not be null.");
        Objects.requireNonNull(key, "The association key may not be null.");
        Objects.requireNonNull(value, "The association value may not be null.");
        return configuration -> assertAssociationPresent(sagaStoreOf(configuration),
                                                         sagaType,
                                                         key,
                                                         value.toString());
    }

    /**
     * Asserts that no Saga of the given {@code sagaType} is associated with the given {@code key} and {@code value}.
     * <p>
     * The {@code value} is compared by its {@link Object#toString() string representation}, as Axon Framework 4 did,
     * because that is the form an {@link AssociationValue} holds.
     *
     * @param sagaType the type of Saga to look for
     * @param key      the key of the association
     * @param value    the value of the association, compared by its string representation
     * @return an assertion to hand to {@link AxonTestPhase.Then.MessageAssertions#expect(Consumer)}
     */
    public static Consumer<Configuration> noAssociationWith(Class<?> sagaType, String key, Object value) {
        Objects.requireNonNull(sagaType, "The sagaType may not be null.");
        Objects.requireNonNull(key, "The association key may not be null.");
        Objects.requireNonNull(value, "The association value may not be null.");
        return configuration -> assertNoAssociationPresent(sagaStoreOf(configuration),
                                                           sagaType,
                                                           key,
                                                           value.toString());
    }

    static void assertActiveSagas(SagaStore<Object> sagaStore, int expected) {
        if (!(sagaStore instanceof InMemorySagaStore inMemorySagaStore)) {
            throw new FixtureExecutionException(format(
                    "Cannot count the sagas held by a [%s]. The number of active sagas is only available from an %s.",
                    sagaStore.getClass().getName(),
                    InMemorySagaStore.class.getSimpleName()
            ));
        }
        int actual = inMemorySagaStore.size();
        if (expected != actual) {
            throw new AxonAssertionError(format(
                    "Wrong number of active sagas.\nExpected <%s>,\n but got <%s>.", expected, actual
            ));
        }
    }

    static void assertAssociationPresent(SagaStore<Object> sagaStore,
                                         Class<?> sagaType,
                                         String key,
                                         String value) {
        if (findSagas(sagaStore, sagaType, key, value).isEmpty()) {
            throw new AxonAssertionError(format(
                    "Expected a saga to be associated with key:<%s> value:<%s>, but found <none>", key, value
            ));
        }
    }

    static void assertNoAssociationPresent(SagaStore<Object> sagaStore,
                                           Class<?> sagaType,
                                           String key,
                                           String value) {
        Set<String> associatedSagas = findSagas(sagaStore, sagaType, key, value);
        if (!associatedSagas.isEmpty()) {
            throw new AxonAssertionError(format(
                    "Expected no sagas to be associated with key:<%s> value:<%s>, but found <%s>",
                    key, value, associatedSagas.size()
            ));
        }
    }

    private static Set<String> findSagas(SagaStore<Object> sagaStore,
                                         Class<?> sagaType,
                                         String key,
                                         String value) {
        return sagaStore.findSagas(sagaType, new AssociationValue(key, value));
    }

    /**
     * A {@link SagaStore} is registered as a component under its raw type, as a configuration cannot key a component
     * on the Saga type it holds. The assertions here only read from the store, so widening it to {@code Object} is
     * safe.
     */
    @SuppressWarnings("unchecked")
    private static SagaStore<Object> sagaStoreOf(Configuration configuration) {
        Objects.requireNonNull(configuration, "The configuration may not be null.");
        return (SagaStore<Object>) configuration.getOptionalComponent(SagaStore.class)
                            .orElseThrow(() -> new FixtureExecutionException(format(
                                    "No component of type [%s] is registered, so there are no sagas to assert on. "
                                            + "Register the store the saga repository under test writes to.",
                                    SagaStore.class.getName()
                            )));
    }
}
