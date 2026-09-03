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
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.nio.charset.StandardCharsets;

import org.axonframework.modelling.saga.repository.Af4CompatibilityTestSuite;

/**
 * Verifies that {@link JpaSagaStore} satisfies {@link Af4CompatibilityTestSuite} against an HSQLDB table in the Axon
 * Framework 4 layout.
 */
class JpaSagaStoreAf4CompatibilityTest extends Af4CompatibilityTestSuite {

    // Hand-inserted association rows need identifiers the store's own generator will not reach, since these rows are
    // written around JPA rather than through it.
    private static final long FIRST_SEEDED_ASSOCIATION_ID = 1_000_000L;

    private long nextAssociationId = FIRST_SEEDED_ASSOCIATION_ID;
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

        seedAf4Rows();
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
        entityManager.getTransaction().begin();
        try {
            operation.run();
            entityManager.getTransaction().commit();
        } catch (RuntimeException e) {
            entityManager.getTransaction().rollback();
            throw e;
        }
    }

    @Override
    protected void insertAf4Saga(String sagaId, String sagaType, String revision, String serializedSaga) {
        entityManager.createNativeQuery(
                             "INSERT INTO SagaEntry (sagaId, revision, sagaType, serializedSaga) VALUES (?, ?, ?, ?)")
                     .setParameter(1, sagaId)
                     .setParameter(2, revision)
                     .setParameter(3, sagaType)
                     .setParameter(4, serializedSaga.getBytes(StandardCharsets.UTF_8))
                     .executeUpdate();
    }

    @Override
    protected void insertAf4Association(String sagaId, String sagaType, AssociationValue associationValue) {
        entityManager.createNativeQuery(
                             "INSERT INTO AssociationValueEntry (id, associationKey, associationValue, sagaId, sagaType) "
                                     + "VALUES (?, ?, ?, ?, ?)")
                     .setParameter(1, nextAssociationId++)
                     .setParameter(2, associationValue.getKey())
                     .setParameter(3, associationValue.getValue())
                     .setParameter(4, sagaId)
                     .setParameter(5, sagaType)
                     .executeUpdate();
    }

    @Override
    protected String columnOf(String column, String sagaId) {
        entityManager.clear();
        Object value = entityManager.createNativeQuery(
                                            "SELECT " + column + " FROM SagaEntry WHERE sagaId = ?")
                                    .setParameter(1, sagaId)
                                    .getSingleResult();
        return value == null ? null : value.toString();
    }
}
