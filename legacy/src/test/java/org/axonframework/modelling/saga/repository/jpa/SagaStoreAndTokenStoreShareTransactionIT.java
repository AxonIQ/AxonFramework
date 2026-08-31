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
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStoreConfiguration;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.repository.StubSaga;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that a saga store and a token store placed in one unit of work end up in the same transaction, even though
 * they reach their {@link EntityManager} by different routes.
 * <p>
 * This is the point the saga store port turns on. {@link JpaTokenStore} is built the way Axon Framework 5 components
 * are: it holds a {@link JpaTransactionalExecutorProvider} and is handed the {@link org.axonframework.messaging.core.unitofwork.ProcessingContext}
 * on every call, from which it pulls the executor the transaction manager published. {@link JpaSagaStore} keeps the Axon
 * Framework 4 shape: it holds an {@link EntityManagerProvider} and takes no context at all. If those two routes did not
 * converge, the two stores would be writing in different transactions and only one of them would follow the unit of
 * work.
 * <p>
 * They do converge, because {@code EntityManagerExecutor} is a wrapper over the same provider the transaction manager
 * began its transaction on. This test is what makes that a demonstrated fact rather than a reading of the code.
 */
class SagaStoreAndTokenStoreShareTransactionIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String PROCESSOR = "saga-processor";
    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");

    private EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private JpaSagaStore sagaStore;
    private TokenStore tokenStore;
    private UnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void setUp() {
        entityManagerFactory = Persistence.createEntityManagerFactory("jpaSagaStorePersistenceUnit");
        entityManager = entityManagerFactory.createEntityManager();
        EntityManagerProvider entityManagerProvider = new SimpleEntityManagerProvider(entityManager);

        // Axon Framework 4 shape: holds the provider, takes no ProcessingContext.
        sagaStore = JpaSagaStore.builder()
                                .entityManagerProvider(entityManagerProvider)
                                .converter(new JacksonConverter())
                                .build();

        // Axon Framework 5 shape: resolves its EntityManager from the ProcessingContext on every call.
        tokenStore = new JpaTokenStore(new JpaTransactionalExecutorProvider(entityManagerFactory),
                                       new JacksonConverter(),
                                       JpaTokenStoreConfiguration.DEFAULT);

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

    private long committedSagaCount() {
        EntityManager reader = entityManagerFactory.createEntityManager();
        try {
            return reader.createQuery("SELECT COUNT(se) FROM SagaEntry se", Long.class).getSingleResult();
        } finally {
            reader.close();
        }
    }

    private long committedTokenCount() {
        EntityManager reader = entityManagerFactory.createEntityManager();
        try {
            return reader.createQuery("SELECT COUNT(te) FROM TokenEntry te", Long.class).getSingleResult();
        } finally {
            reader.close();
        }
    }

    @Test
    void bothStoresCommitTogether() {
        // given a unit of work writing a token through the context and a saga through the provider
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.onInvocation(context -> tokenStore.initializeTokenSegments(PROCESSOR, 1, null, context)
                                                     .thenRun(() -> sagaStore.insertSaga(StubSaga.class,
                                                                                         "saga-1",
                                                                                         new StubSaga(),
                                                                                         singleton(ORDER_1))));

        // when
        FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT);

        // then
        assertThat(committedTokenCount()).isEqualTo(1);
        assertThat(committedSagaCount()).isEqualTo(1);
    }

    @Test
    void neitherStoreCommitsWhenTheUnitOfWorkFails() {
        // given the same two writes, followed by a failure
        UnitOfWork unitOfWork = unitOfWorkFactory.create();
        unitOfWork.onInvocation(context -> tokenStore.initializeTokenSegments(PROCESSOR, 1, null, context)
                                                     .thenRun(() -> sagaStore.insertSaga(StubSaga.class,
                                                                                         "saga-1",
                                                                                         new StubSaga(),
                                                                                         singleton(ORDER_1))));
        unitOfWork.onPrepareCommit(context -> CompletableFuture.failedFuture(
                new IllegalStateException("failing the unit of work on purpose")));

        // when
        assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(unitOfWork.execute(), TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failing the unit of work on purpose");

        // then neither write survived. Had the two stores been in separate transactions, the rollback would have
        // discarded one and left the other behind.
        assertThat(committedTokenCount()).isZero();
        assertThat(committedSagaCount()).isZero();
    }
}
