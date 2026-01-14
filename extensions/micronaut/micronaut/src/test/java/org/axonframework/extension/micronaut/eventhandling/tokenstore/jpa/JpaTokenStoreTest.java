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

package org.axonframework.extension.micronaut.eventhandling.tokenstore.jpa;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.transaction.SynchronousTransactionManager;
import io.micronaut.transaction.TransactionDefinition;
import io.micronaut.transaction.TransactionStatus;
import io.micronaut.transaction.annotation.Transactional;
import io.micronaut.transaction.support.DefaultTransactionDefinition;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.conversion.TestConverter;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStoreConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.TokenEntry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.micronaut.core.util.StringUtils.FALSE;
import static io.micronaut.core.util.StringUtils.TRUE;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.junit.jupiter.api.Assertions.*;


@Property(name = "datasources.default.enabled",value = TRUE)
@Property(name = "datasources.default.dialect",value = "ANSI")
@Property(name = "datasources.default.url",value = "jdbc:hsqldb:mem:testdb")
@Property(name = "jpa.default.properties.hibernate.hbm2ddl.auto",value = "create-drop")
@Property(name = "jpa.default.properties.hibernate.show_sql",value = FALSE)
@Property(name = "jpa.default.properties.connection.url",value = "jdbc:hsqldb:mem:testdb")
@Property(name = "jpa.default.entity-scan.packages",value = "org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa")
@MicronautTest(startApplication = false)
class JpaTokenStoreTest {

    @Inject
    @Named("jpaTokenStore")
    private JpaTokenStore jpaTokenStore;

    @Inject
    @Named("stealingJpaTokenStore")
    private JpaTokenStore stealingJpaTokenStore;

    @PersistenceContext
    private EntityManager entityManager;

    @Inject
    private SynchronousTransactionManager<Object> transactionManager;

    @Transactional
    @Test
    void stealingFromOtherThreadFailsWithRowLock() throws Exception {
        joinAndUnwrap(jpaTokenStore.initializeTokenSegments("processor", 1, null, null));

        ExecutorService executor1 = Executors.newSingleThreadExecutor();
        CountDownLatch cdl = new CountDownLatch(1);
        try {
            jpaTokenStore.fetchToken("processor", 0, null);
            Future<?> result = executor1.submit(() -> {
                DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
                txDef.setPropagationBehavior(TransactionDefinition.Propagation.REQUIRES_NEW);
                TransactionStatus<Object> tx = transactionManager.getTransaction(txDef);
                cdl.countDown();
                try {
                    joinAndUnwrap(stealingJpaTokenStore.fetchToken("processor", 0, null));
                } finally {
                    transactionManager.rollback(tx);
                }
            });
            cdl.await();
            try {
                result.get(250, TimeUnit.MILLISECONDS);
                fail("Expected task to time out on the write lock");
            } catch (TimeoutException e) {
                // we expect this;
            }
            assertFalse(result.isDone());

            // we cancel the task
            result.cancel(true);

            // and make sure the token is still owned
            TokenEntry tokenEntry = entityManager.find(TokenEntry.class, new TokenEntry.PK("processor", 0));
            assertEquals("local", tokenEntry.getOwner());
        } finally {
            executor1.shutdown();
        }
    }

    @Factory
    public static class Context {
        @Singleton
        public JpaTokenStore jpaTokenStore(EntityManagerProvider entityManagerProvider) {
            var config = JpaTokenStoreConfiguration.DEFAULT.nodeId("local");
            return new JpaTokenStore(entityManagerProvider, TestConverter.JACKSON.getConverter(), config);
        }

        @Singleton
        public JpaTokenStore stealingJpaTokenStore(EntityManagerProvider entityManagerProvider) {
            var config = JpaTokenStoreConfiguration.DEFAULT.nodeId("stealing").claimTimeout(Duration.ofSeconds(-1));
            return new JpaTokenStore(entityManagerProvider, TestConverter.JACKSON.getConverter(), config);
        }

        @Singleton
        public TransactionManager transactionManager(SynchronousTransactionManager<Object> txManager) {
            //noinspection Duplicates
            return () -> {
                TransactionStatus<Object> transaction = txManager.getTransaction(new DefaultTransactionDefinition());
                return new Transaction() {
                    @Override
                    public void commit() {
                        txManager.commit(transaction);
                    }

                    @Override
                    public void rollback() {
                        txManager.rollback(transaction);
                    }
                };
            };
        }

        @Factory
        public static class PersistenceConfig {

            @PersistenceContext
            private EntityManager entityManager;

            @Singleton
            public EntityManagerProvider entityManagerProvider() {
                return new SimpleEntityManagerProvider(entityManager);
            }
        }
    }
}