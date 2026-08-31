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

package org.axonframework.modelling.saga.repository;

import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValuesImpl;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract that every {@link SagaStore} implementation is expected to satisfy.
 * <p>
 * Subclasses supply the store under test through {@link #testSubject()} and are otherwise free of setup: the tests here
 * exercise only the {@code SagaStore} API, so they say nothing about how a particular implementation persists.
 *
 * @author Allard Buijze
 */
public abstract class SagaStoreTestSuite {

    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");
    private static final AssociationValue ORDER_2 = new AssociationValue("orderId", "order-2");
    private static final AssociationValue SHIPMENT_1 = new AssociationValue("shipmentId", "shipment-1");

    /**
     * Returns the {@link SagaStore} under test. Called once per test method, after any subclass setup has run.
     *
     * @return the store to verify
     */
    protected abstract SagaStore<Object> testSubject();

    /**
     * Runs the given {@code operation} against the store. Overridden by subclasses that need the call to happen inside a
     * transaction; by default it simply runs.
     *
     * @param operation the operation to run
     */
    protected void inTransaction(Runnable operation) {
        operation.run();
    }

    private static StubSaga sagaHandling(String... events) {
        StubSaga saga = new StubSaga();
        for (String event : events) {
            saga.handled(event);
        }
        return saga;
    }

    @Nested
    class InsertSaga {

        @Test
        void insertedSagaIsLoadedBackWithItsStateAndAssociations() {
            // given
            StubSaga saga = sagaHandling("OrderPlaced", "OrderPaid");

            // when
            inTransaction(() -> testSubject().insertSaga(StubSaga.class, "saga-1", saga, Set.of(ORDER_1, SHIPMENT_1)));

            // then
            SagaStore.Entry<StubSaga> entry = testSubject().loadSaga(StubSaga.class, "saga-1");
            assertThat(entry).isNotNull();
            assertThat(entry.saga()).isEqualTo(saga);
            assertThat(entry.saga().getHandledEvents()).containsExactly("OrderPlaced", "OrderPaid");
            assertThat(entry.associationValues()).containsExactlyInAnyOrder(ORDER_1, SHIPMENT_1);
        }

        @Test
        void sagaWithoutAssociationsIsLoadedBackWithNone() {
            // given / when
            inTransaction(() -> testSubject().insertSaga(StubSaga.class, "saga-1", sagaHandling(), emptySet()));

            // then
            SagaStore.Entry<StubSaga> entry = testSubject().loadSaga(StubSaga.class, "saga-1");
            assertThat(entry).isNotNull();
            assertThat(entry.associationValues()).isEmpty();
        }
    }

    @Nested
    class LoadSaga {

        @Test
        void unknownIdentifierYieldsNull() {
            // given no saga stored / when / then
            assertThat(testSubject().loadSaga(StubSaga.class, "no-such-saga")).isNull();
        }
    }

    @Nested
    class FindSagas {

        @Test
        void findsOnlyTheSagasAssociatedWithTheGivenValue() {
            // given
            inTransaction(() -> {
                testSubject().insertSaga(StubSaga.class, "saga-1", sagaHandling(), singleton(ORDER_1));
                testSubject().insertSaga(StubSaga.class, "saga-2", sagaHandling(), singleton(ORDER_2));
                testSubject().insertSaga(StubSaga.class, "saga-3", sagaHandling(), singleton(ORDER_1));
            });

            // when
            Set<String> found = testSubject().findSagas(StubSaga.class, ORDER_1);

            // then
            assertThat(found).containsExactlyInAnyOrder("saga-1", "saga-3");
        }

        @Test
        void unknownAssociationYieldsEmptySet() {
            // given
            inTransaction(() -> testSubject().insertSaga(StubSaga.class, "saga-1", sagaHandling(), singleton(ORDER_1)));

            // when / then
            assertThat(testSubject().findSagas(StubSaga.class, ORDER_2)).isEmpty();
        }
    }

    @Nested
    class UpdateSaga {

        @Test
        void updatedStateIsLoadedBack() {
            // given
            inTransaction(() -> testSubject().insertSaga(StubSaga.class,
                                                        "saga-1",
                                                        sagaHandling("OrderPlaced"),
                                                        singleton(ORDER_1)));

            // when
            StubSaga updated = sagaHandling("OrderPlaced", "OrderShipped");
            inTransaction(() -> testSubject().updateSaga(StubSaga.class,
                                                         "saga-1",
                                                         updated,
                                                         new AssociationValuesImpl(singleton(ORDER_1))));

            // then
            SagaStore.Entry<StubSaga> entry = testSubject().loadSaga(StubSaga.class, "saga-1");
            assertThat(entry).isNotNull();
            assertThat(entry.saga().getHandledEvents()).containsExactly("OrderPlaced", "OrderShipped");
        }

        @Test
        void addedAssociationBecomesFindable() {
            // given a saga associated with an order only
            inTransaction(() -> testSubject().insertSaga(StubSaga.class,
                                                         "saga-1",
                                                         sagaHandling(),
                                                         singleton(ORDER_1)));
            AssociationValuesImpl associations = new AssociationValuesImpl(singleton(ORDER_1));

            // when a shipment association is added
            associations.add(SHIPMENT_1);
            inTransaction(() -> testSubject().updateSaga(StubSaga.class, "saga-1", sagaHandling(), associations));

            // then it is findable by both, and both come back on load
            assertThat(testSubject().findSagas(StubSaga.class, ORDER_1)).containsExactly("saga-1");
            assertThat(testSubject().findSagas(StubSaga.class, SHIPMENT_1)).containsExactly("saga-1");
            SagaStore.Entry<StubSaga> entry = testSubject().loadSaga(StubSaga.class, "saga-1");
            assertThat(entry).isNotNull();
            assertThat(entry.associationValues()).containsExactlyInAnyOrder(ORDER_1, SHIPMENT_1);
        }

        @Test
        void removedAssociationIsNoLongerFindable() {
            // given a saga associated with both an order and a shipment
            inTransaction(() -> testSubject().insertSaga(StubSaga.class,
                                                         "saga-1",
                                                         sagaHandling(),
                                                         Set.of(ORDER_1, SHIPMENT_1)));
            AssociationValuesImpl associations = new AssociationValuesImpl(Set.of(ORDER_1, SHIPMENT_1));

            // when the shipment association is removed
            associations.remove(SHIPMENT_1);
            inTransaction(() -> testSubject().updateSaga(StubSaga.class, "saga-1", sagaHandling(), associations));

            // then only the order association remains
            assertThat(testSubject().findSagas(StubSaga.class, SHIPMENT_1)).isEmpty();
            assertThat(testSubject().findSagas(StubSaga.class, ORDER_1)).containsExactly("saga-1");
            SagaStore.Entry<StubSaga> entry = testSubject().loadSaga(StubSaga.class, "saga-1");
            assertThat(entry).isNotNull();
            assertThat(entry.associationValues()).containsExactly(ORDER_1);
        }

        // Updating a saga that is not stored is deliberately absent from this suite: the three implementations
        // inherited from Axon Framework 4 disagree, so each pins its own behaviour instead. InMemorySagaStore creates
        // the saga, JdbcSagaStore guards on the update count and changes nothing, and JpaSagaStore updates no saga row
        // but still applies the association changes.
    }

    @Nested
    class DeleteSaga {

        @Test
        void deletedSagaAndItsAssociationsAreGone() {
            // given
            inTransaction(() -> testSubject().insertSaga(StubSaga.class,
                                                         "saga-1",
                                                         sagaHandling(),
                                                         Set.of(ORDER_1, SHIPMENT_1)));

            // when
            inTransaction(() -> testSubject().deleteSaga(StubSaga.class, "saga-1", Set.of(ORDER_1, SHIPMENT_1)));

            // then
            assertThat(testSubject().loadSaga(StubSaga.class, "saga-1")).isNull();
            assertThat(testSubject().findSagas(StubSaga.class, ORDER_1)).isEmpty();
            assertThat(testSubject().findSagas(StubSaga.class, SHIPMENT_1)).isEmpty();
        }

        @Test
        void deletingOneSagaLeavesTheOtherIntact() {
            // given two sagas sharing an association value
            inTransaction(() -> {
                testSubject().insertSaga(StubSaga.class, "saga-1", sagaHandling(), singleton(ORDER_1));
                testSubject().insertSaga(StubSaga.class, "saga-2", sagaHandling(), singleton(ORDER_1));
            });

            // when
            inTransaction(() -> testSubject().deleteSaga(StubSaga.class, "saga-1", singleton(ORDER_1)));

            // then
            assertThat(testSubject().loadSaga(StubSaga.class, "saga-1")).isNull();
            assertThat(testSubject().loadSaga(StubSaga.class, "saga-2")).isNotNull();
            assertThat(testSubject().findSagas(StubSaga.class, ORDER_1)).containsExactly("saga-2");
        }
    }
}
