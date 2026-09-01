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

package org.axonframework.modelling.saga.repository.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.EntityManagerTransactionManager;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValuesImpl;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.StubSaga;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link JpaSagaStore} takes part in a transaction driven by a real
 * {@link TransactionalUnitOfWorkFactory} against a real database.
 * <p>
 * The other tests in this module drive the {@code EntityTransaction} by hand, which shows the store works but says
 * nothing about the framework wiring. Here the transaction is started, committed and rolled back by the unit of work
 * itself, through an {@link EntityManagerTransactionManager}, exactly as it would be in an application. The store is
 * given the same {@link EntityManagerProvider} the transaction manager holds, which is the mechanism by which it joins.
 * The processing context passed to the store does not replace that provider and the store does not use a
 * {@code TransactionalExecutorProvider}.
 * <p>
 * Every assertion reads through a second {@link EntityManager} with its own persistence context, so what is being
 * observed is committed database state rather than the writing session's cache.
 */
class JpaSagaStoreTransactionalUnitOfWorkIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");
    private static final AssociationValue ORDER_2 = new AssociationValue("orderId", "order-2");

    private EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaSagaStore testSubject;
    private UnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        entityManagerFactory = Persistence.createEntityManagerFactory("jpaSagaStorePersistenceUnit");
        entityManager = entityManagerFactory.createEntityManager();
        EntityManagerProvider entityManagerProvider = new SimpleEntityManagerProvider(entityManager);

        testSubject = JpaSagaStore.builder()
                                  .entityManagerProvider(entityManagerProvider)
                                  .converter(new JacksonConverter())
                                  .build();

        // The transaction manager and the store share one EntityManagerProvider, as they would when both are wired
        // from the same bean. Nothing else connects them.
        unitOfWorkFactory = new TransactionalUnitOfWorkFactory(
                new EntityManagerTransactionManager(entityManagerProvider),
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
        );
    }

    @AfterEach
    void tearDown() {
        entityManager.close();
        entityManagerFactory.close();
    }

    /**
     * Reads through a persistence context that took no part in the unit of work, so only committed state is visible.
     */
    private List<String> committedSagaIds() {
        EntityManager reader = entityManagerFactory.createEntityManager();
        try {
            return reader.createQuery("SELECT se.sagaId FROM SagaEntry se ORDER BY se.sagaId", String.class)
                         .getResultList();
        } finally {
            reader.close();
        }
    }

    private List<String> committedAssociationSagaIds(AssociationValue associationValue) {
        EntityManager reader = entityManagerFactory.createEntityManager();
        try {
            return reader.createQuery("SELECT ae.sagaId FROM AssociationValueEntry ae "
                                              + "WHERE ae.associationKey = :key AND ae.associationValue = :value",
                                      String.class)
                         .setParameter("key", associationValue.getKey())
                         .setParameter("value", associationValue.getValue())
                         .getResultList();
        } finally {
            reader.close();
        }
    }

    @Nested
    class WhenTheUnitOfWorkCommits {

        @Test
        void theSagaIsPersisted() {
            // given a unit of work that inserts a saga
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                         "saga-1",
                                                                         new StubSaga(),
                                                                         singleton(ORDER_1)));

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

            // then the unit of work committed the transaction the store wrote in
            assertThat(committedSagaIds()).containsExactly("saga-1");
            assertThat(committedAssociationSagaIds(ORDER_1)).containsExactly("saga-1");
        }

        @Test
        void everythingWrittenAcrossPhasesIsPersistedTogether() {
            // given a unit of work writing one saga during invocation and another at prepare-commit
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                         "saga-1",
                                                                         new StubSaga(),
                                                                         singleton(ORDER_1)));
            unitOfWork.runOnPrepareCommit(context -> testSubject.insertSaga(StubSaga.class,
                                                                            "saga-2",
                                                                            new StubSaga(),
                                                                            singleton(ORDER_2)));

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

            // then both landed in the one transaction
            assertThat(committedSagaIds()).containsExactly("saga-1", "saga-2");
        }

        @Test
        void aSagaUpdatedInALaterUnitOfWorkIsPersisted() {
            // given a saga committed by one unit of work
            UnitOfWork insertingUnitOfWork = unitOfWorkFactory.create();
            insertingUnitOfWork.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                                  "saga-1",
                                                                                  new StubSaga(),
                                                                                  singleton(ORDER_1)));
            FutureUtils.joinAndUnwrap(insertingUnitOfWork.execute(), TIMEOUT);

            // when a second unit of work loads and updates it
            StubSaga updated = new StubSaga();
            updated.handled("OrderShipped");
            UnitOfWork updatingUnitOfWork = unitOfWorkFactory.create();
            updatingUnitOfWork.runOnInvocation(context -> {
                SagaStore.Entry<StubSaga> entry = testSubject.loadSaga(StubSaga.class, "saga-1");
                assertThat(entry).isNotNull();
                testSubject.updateSaga(StubSaga.class,
                                       "saga-1",
                                       updated,
                                       new AssociationValuesImpl(singleton(ORDER_1)));
            });
            FutureUtils.joinAndUnwrap(updatingUnitOfWork.execute(), TIMEOUT);

            // then the new state is committed
            EntityManager reader = entityManagerFactory.createEntityManager();
            try {
                JpaSagaStore readerStore = JpaSagaStore.builder()
                                                       .entityManagerProvider(new SimpleEntityManagerProvider(reader))
                                                       .converter(new JacksonConverter())
                                                       .build();
                SagaStore.Entry<StubSaga> entry = readerStore.loadSaga(StubSaga.class, "saga-1");
                assertThat(entry).isNotNull();
                assertThat(entry.saga().getHandledEvents()).containsExactly("OrderShipped");
            } finally {
                reader.close();
            }
        }
    }

    @Nested
    class WhenTheUnitOfWorkFails {

        @Test
        void theSagaIsNotPersisted() {
            // given a unit of work that inserts a saga and then fails
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                         "saga-1",
                                                                         new StubSaga(),
                                                                         singleton(ORDER_1)));
            unitOfWork.onPrepareCommit(context -> CompletableFuture.failedFuture(
                    new IllegalStateException("failing the unit of work on purpose")));

            // when
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("failing the unit of work on purpose");

            // then the rollback took the saga with it, which it could only do if the store wrote in the unit of
            // work's transaction rather than one of its own
            assertThat(committedSagaIds()).isEmpty();
            assertThat(committedAssociationSagaIds(ORDER_1)).isEmpty();
        }

        @Test
        void aSagaWrittenBeforeTheFailingPhaseIsNotPersistedEither() {
            // given two sagas written in separate phases, the second of which fails after the first was written
            UnitOfWork unitOfWork = unitOfWorkFactory.create();
            unitOfWork.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                         "saga-1",
                                                                         new StubSaga(),
                                                                         singleton(ORDER_1)));
            unitOfWork.onPrepareCommit(context -> {
                testSubject.insertSaga(StubSaga.class, "saga-2", new StubSaga(), singleton(ORDER_2));
                return CompletableFuture.failedFuture(new IllegalStateException("failing after the second write"));
            });

            // when
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT))
                    .isInstanceOf(IllegalStateException.class);

            // then neither survived, so the two writes really shared one transaction
            assertThat(committedSagaIds()).isEmpty();
        }

        @Test
        void anEarlierCommittedSagaIsUnaffected() {
            // given a saga committed by an earlier unit of work
            UnitOfWork committed = unitOfWorkFactory.create();
            committed.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                        "saga-1",
                                                                        new StubSaga(),
                                                                        singleton(ORDER_1)));
            FutureUtils.joinAndUnwrap(committed.execute(), TIMEOUT);

            // when a later unit of work writes and then fails
            UnitOfWork failing = unitOfWorkFactory.create();
            failing.runOnInvocation(context -> testSubject.insertSaga(StubSaga.class,
                                                                      "saga-2",
                                                                      new StubSaga(),
                                                                      singleton(ORDER_2)));
            failing.onPrepareCommit(context -> CompletableFuture.failedFuture(new IllegalStateException("boom")));
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(failing.execute(), TIMEOUT))
                    .isInstanceOf(IllegalStateException.class);

            // then only the failed unit of work's write was discarded
            assertThat(committedSagaIds()).containsExactly("saga-1");
        }
    }
}
