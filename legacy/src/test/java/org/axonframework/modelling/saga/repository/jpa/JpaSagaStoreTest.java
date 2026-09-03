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
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;
import jakarta.persistence.TransactionRequiredException;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.AssociationValuesImpl;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.SagaStoreTestSuite;
import org.axonframework.modelling.saga.repository.StubSaga;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link JpaSagaStore}.
 * <p>
 * Writes run inside an {@link EntityTransaction} because JPA requires one; reads run outside, which is what makes the
 * assertions reflect the database rather than the persistence context.
 *
 * @author Mateusz Nowak
 */
class JpaSagaStoreTest extends SagaStoreTestSuite {

    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");

    private EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaSagaStore testSubject;

    @BeforeEach
    void setUp() {
        entityManagerFactory = Persistence.createEntityManagerFactory("jpaSagaStorePersistenceUnit");
        entityManager = entityManagerFactory.createEntityManager();
        testSubject = JpaSagaStore.builder()
                                  .entityManagerProvider(new SimpleEntityManagerProvider(entityManager))
                                  .converter(new JacksonConverter())
                                  .build();
    }

    @AfterEach
    void tearDown() {
        entityManager.close();
        entityManagerFactory.close();
    }

    @Override
    protected SagaStore<Object> testSubject() {
        return testSubject;
    }

    @Override
    protected void inTransaction(Runnable operation) {
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        try {
            operation.run();
        } catch (RuntimeException e) {
            transaction.rollback();
            throw e;
        }
        transaction.commit();
        // The store's bulk JPQL updates bypass the persistence context, so drop it rather than let a stale managed
        // entity answer a later read.
        entityManager.clear();
    }

    /**
     * Pins behaviour that differs per implementation, and is therefore not part of {@link SagaStoreTestSuite}. This
     * store updates no saga row but applies the association changes regardless, leaving an association pointing at a
     * saga that does not exist; {@code InMemorySagaStore} creates the saga and {@code JdbcSagaStore} changes nothing.
     */
    @Test
    void updatingAnAbsentSagaStoresItsAddedAssociationsAnyway() {
        // given no saga stored, and an association pending addition
        AssociationValuesImpl associations = new AssociationValuesImpl();
        associations.add(ORDER_1);

        // when
        inTransaction(() -> testSubject.updateSaga(StubSaga.class, "saga-1", new StubSaga(), associations));

        // then no saga came into being, yet the association was written, leaving it pointing at nothing
        assertThat(testSubject.loadSaga(StubSaga.class, "saga-1")).isNull();
        assertThat(testSubject.findSagas(StubSaga.class, ORDER_1)).containsExactly("saga-1");
    }

    @Test
    void writingWithoutATransactionFailsRatherThanWritingPartialState() {
        // given no active transaction / when / then
        assertThatThrownBy(() -> testSubject.insertSaga(
                StubSaga.class, "saga-1", new StubSaga(), singleton(ORDER_1)))
                .isInstanceOf(TransactionRequiredException.class);
        assertThat(testSubject.loadSaga(StubSaga.class, "saga-1")).isNull();
    }

    @Test
    void rollingBackTheTransactionDiscardsTheInsertedSaga() {
        // given a saga inserted inside a transaction that is then rolled back
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        testSubject.insertSaga(StubSaga.class, "saga-1", new StubSaga(), singleton(ORDER_1));
        transaction.rollback();
        entityManager.clear();

        // when / then nothing was persisted, so the store did join the transaction
        assertThat(testSubject.loadSaga(StubSaga.class, "saga-1")).isNull();
        assertThat(testSubject.findSagas(StubSaga.class, ORDER_1)).isEmpty();
    }
}
