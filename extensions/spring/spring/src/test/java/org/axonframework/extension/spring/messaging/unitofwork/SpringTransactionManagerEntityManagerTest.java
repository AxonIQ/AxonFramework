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

package org.axonframework.extension.spring.messaging.unitofwork;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for how {@link SpringTransactionManager} treats the entity manager of a unit of work, with Hibernate on a
 * pool of a single connection. An entity manager that is looked up on another thread does not take part in the
 * transaction of the unit of work, and needs a connection of its own.
 *
 * @author John Hendrikx
 */
class SpringTransactionManagerEntityManagerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private HikariDataSource pool;
    private EntityManagerFactory entityManagerFactory;
    private JdbcTemplate jdbcTemplate;
    private SpringTransactionManager testSubject;
    private JpaTransactionalExecutorProvider provider;

    @BeforeEach
    void beforeEach() {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl("jdbc:hsqldb:mem:" + UUID.randomUUID());
        config.setUsername("SA");
        config.setPassword("");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(1000);
        config.setAutoCommit(true);

        pool = new HikariDataSource(config);

        LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();

        factoryBean.setDataSource(pool);
        factoryBean.setManagedTypes(PersistenceManagedTypes.of(ConnectionTestEntity.class.getName()));
        factoryBean.setPersistenceUnitName("entity-manager-test");
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

        Properties properties = new Properties();

        properties.setProperty("hibernate.hbm2ddl.auto", "create");
        factoryBean.setJpaProperties(properties);
        factoryBean.afterPropertiesSet();

        entityManagerFactory = factoryBean.getObject();

        JpaTransactionManager transactionManager = new JpaTransactionManager(entityManagerFactory);

        transactionManager.setDataSource(pool);
        transactionManager.afterPropertiesSet();

        EntityManager sharedEntityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        EntityManagerProvider entityManagerProvider = new SimpleEntityManagerProvider(sharedEntityManager);

        testSubject = new SpringTransactionManager(transactionManager, entityManagerProvider, null);
        provider = new JpaTransactionalExecutorProvider(entityManagerFactory);
        jdbcTemplate = new JdbcTemplate(pool);
    }

    @AfterEach
    void afterEach() {
        entityManagerFactory.close();
        pool.close();
    }

    private CompletableFuture<Void> inUnitOfWork(Consumer<ProcessingContext> work) {
        UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();

        testSubject.attachToProcessingLifecycle(unitOfWork);
        unitOfWork.runOnInvocation(work);

        return unitOfWork.execute();
    }

    private void persistThroughTheUnitOfWorkOn(ProcessingContext context, long id) {
        provider.getTransactionalExecutor(context).apply(entityManager -> {
            entityManager.persist(new ConnectionTestEntity(id, "entity-" + id));

            return null;
        }).join();
    }

    private void onAnotherThread(Runnable work) {
        CompletableFuture.runAsync(work).orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
    }

    private int rows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM connection_test_entity", Integer.class);
    }

    @Nested
    class OnTheThreadOfTheUnitOfWork {

        @Test
        void shouldWriteThroughTheTransactionOfTheUnitOfWork() {
            // when
            CompletableFuture<Void> result = inUnitOfWork(context -> persistThroughTheUnitOfWorkOn(context, 1));

            // then
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(rows()).isEqualTo(1);
        }

        @Test
        void shouldRollBackWhatWasWrittenWhenTheUnitOfWorkFails() {
            // when
            CompletableFuture<Void> result = inUnitOfWork(context -> {
                persistThroughTheUnitOfWorkOn(context, 1);

                throw new IllegalStateException("failing on purpose");
            });

            // then
            assertThat(result).failsWithin(TIMEOUT);
            assertThat(rows()).isZero();
        }
    }

    @Nested
    class OnAnotherThread {

        @Test
        void shouldWriteThroughTheTransactionOfTheUnitOfWork() {
            // when
            CompletableFuture<Void> result = inUnitOfWork(context ->
                onAnotherThread(() -> persistThroughTheUnitOfWorkOn(context, 1))
            );

            // then
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(rows()).isEqualTo(1);
        }

        @Test
        void shouldRollBackWhatWasWrittenWhenTheUnitOfWorkFails() {
            // given
            AtomicBoolean written = new AtomicBoolean();

            // when
            CompletableFuture<Void> result = inUnitOfWork(context -> {
                onAnotherThread(() -> {
                    persistThroughTheUnitOfWorkOn(context, 1);
                    written.set(true);
                });

                throw new IllegalStateException("failing on purpose");
            });

            // then
            assertThat(result).failsWithin(TIMEOUT);
            assertThat(written).isTrue();
            assertThat(rows()).isZero();
        }

        @Test
        void shouldReadWithoutNeedingAConnectionOfItsOwn() {
            // given
            AtomicInteger found = new AtomicInteger(-1);

            jdbcTemplate.update("INSERT INTO connection_test_entity (id, name) VALUES (7, 'seven')");

            // when
            CompletableFuture<Void> result = inUnitOfWork(context ->
                onAnotherThread(() -> found.set(
                    provider.getTransactionalExecutor(context).apply(entityManager ->
                        entityManager.createQuery("select count(e) from ConnectionTestEntity e", Long.class)
                                     .getSingleResult()
                                     .intValue()
                    ).join()
                ))
            );

            // then
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(found).hasValue(1);
        }
    }
}
