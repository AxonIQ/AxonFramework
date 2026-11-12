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
import org.axonframework.common.jdbc.JdbcException;
import org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider;
import org.axonframework.extension.spring.jdbc.TrackingLazyConnectionDataSource;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for how {@link SpringTransactionManager} treats the connection of a unit of work, on a pool of a single
 * connection, where a unit of work that needs a second connection while it holds the first one cannot proceed.
 *
 * @author John Hendrikx
 */
class SpringTransactionManagerConnectionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private HikariDataSource pool;
    private JdbcTransactionalExecutorProvider provider;

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
        provider = new JdbcTransactionalExecutorProvider(pool);
    }

    @AfterEach
    void afterEach() {
        pool.close();
    }

    private SpringTransactionManager managerOn(DataSource dataSource) {
        return new SpringTransactionManager(
            new DataSourceTransactionManager(dataSource),
            null,
            new SpringDataSourceConnectionProvider(dataSource)
        );
    }

    private CompletableFuture<Void> inUnitOfWork(SpringTransactionManager testSubject,
                                                 Consumer<ProcessingContext> work) {
        UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();

        testSubject.attachToProcessingLifecycle(unitOfWork);
        unitOfWork.runOnInvocation(work);

        return unitOfWork.execute();
    }

    private int activeConnections() {
        return pool.getHikariPoolMXBean().getActiveConnections();
    }

    @Nested
    class WithAPlainDataSource {

        @Test
        void readingThatPrefersAnIndependentConnectionUsesTheConnectionOfTheUnitOfWork() {
            // given
            SpringTransactionManager testSubject = managerOn(pool);
            AtomicInteger activeWhileReading = new AtomicInteger();

            // when
            CompletableFuture<Void> result = inUnitOfWork(testSubject, context ->
                provider.getTransactionalExecutorPreferringIndependent(context).apply(connection -> {
                    activeWhileReading.set(activeConnections());

                    return connection.getAutoCommit();
                }).join()
            );

            // then
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(activeWhileReading).hasValue(1);
            assertThat(activeConnections()).isZero();
        }

        @Test
        void readingOnAnotherThreadUsesTheConnectionOfTheUnitOfWork() {
            // given
            SpringTransactionManager testSubject = managerOn(pool);
            AtomicInteger activeWhileReading = new AtomicInteger();

            // when
            CompletableFuture<Void> result = inUnitOfWork(testSubject, context -> {
                CompletableFuture<Object> read = CompletableFuture.supplyAsync(
                    () -> provider.getTransactionalExecutorPreferringIndependent(context).apply(connection -> {
                        activeWhileReading.set(activeConnections());

                        return connection.getAutoCommit();
                    }).join()
                );

                read.orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS).join();
            });

            // then
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(activeWhileReading).hasValue(1);
        }

        @Test
        void failureToObtainTheConnectionFailsTheUnitOfWorkAndRollsBackTheTransaction() {
            // given
            SpringTransactionManager testSubject = new SpringTransactionManager(
                new DataSourceTransactionManager(pool),
                null,
                () -> {
                    throw new SQLException("no connection for you");
                }
            );

            // when
            CompletableFuture<Void> result = inUnitOfWork(testSubject, context -> {
            });

            // then
            assertThat(result)
                .failsWithin(TIMEOUT)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(JdbcException.class);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(activeConnections()).isZero();
        }
    }

    @Nested
    class WithSpringsLazyConnectionDataSourceProxy {

        @Test
        void shouldTakeAPhysicalConnectionOnlyOnceSomethingUsesIt() {
            // given
            DataSource lazy = new LazyConnectionDataSourceProxy(pool);
            SpringTransactionManager testSubject = managerOn(lazy);
            JdbcTemplate jdbcTemplate = new JdbcTemplate(lazy);
            AtomicInteger activeAfterBegin = new AtomicInteger(-1);
            AtomicInteger activeAfterReading = new AtomicInteger(-1);
            AtomicInteger activeAfterStatement = new AtomicInteger(-1);

            // when
            CompletableFuture<Void> result = inUnitOfWork(testSubject, context -> {
                activeAfterBegin.set(activeConnections());

                provider.getTransactionalExecutorPreferringIndependent(context).apply(connection -> null).join();
                activeAfterReading.set(activeConnections());

                jdbcTemplate.execute("CREATE TABLE lazy_used (id INT)");
                activeAfterStatement.set(activeConnections());
            });

            // then
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(activeAfterBegin).hasValue(0);
            assertThat(activeAfterReading).hasValue(0);
            assertThat(activeAfterStatement).hasValue(1);
            assertThat(activeConnections()).isZero();
        }
    }

    @Nested
    class WithATrackingLazyDataSource {

        private TrackingLazyConnectionDataSource tracking;

        @BeforeEach
        void beforeEach() {
            tracking = new TrackingLazyConnectionDataSource(pool);
        }

        @Test
        void readingUsesAnIndependentConnectionAsLongAsTheUnitOfWorkHoldsNoPhysicalConnection() {
            // given
            SpringTransactionManager testSubject = managerOn(tracking);
            AtomicInteger activeWhileBeginning = new AtomicInteger(-1);
            AtomicInteger activeWhileReading = new AtomicInteger();

            // when
            CompletableFuture<Void> result = inUnitOfWork(testSubject, context -> {
                activeWhileBeginning.set(activeConnections());

                provider.getTransactionalExecutorPreferringIndependent(context).apply(connection -> {
                    activeWhileReading.set(activeConnections());

                    return null;
                }).join();
            });

            // then
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(activeWhileBeginning).hasValue(0);
            assertThat(activeWhileReading).hasValue(1);
            assertThat(activeConnections()).isZero();
        }

        @Test
        void readingUsesTheConnectionOfTheUnitOfWorkOnceItHoldsAPhysicalConnection() {
            // given
            SpringTransactionManager testSubject = managerOn(tracking);
            JdbcTemplate jdbcTemplate = new JdbcTemplate(tracking);
            AtomicInteger activeWhileReading = new AtomicInteger();

            // when
            CompletableFuture<Void> result = inUnitOfWork(testSubject, context -> {
                jdbcTemplate.execute("CREATE TABLE pinned (id INT)");

                provider.getTransactionalExecutorPreferringIndependent(context).apply(connection -> {
                    activeWhileReading.set(activeConnections());

                    return connection.getAutoCommit();
                }).join();
            });

            // then
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(activeWhileReading).hasValue(1);
            assertThat(activeConnections()).isZero();
        }

        @Test
        void unitOfWorkThatNeverUsesTheDatabaseNeverTakesAPhysicalConnection() {
            // given
            SpringTransactionManager testSubject = managerOn(tracking);
            AtomicInteger activeWhileRunning = new AtomicInteger(-1);

            // when
            CompletableFuture<Void> result = inUnitOfWork(testSubject, context ->
                activeWhileRunning.set(activeConnections())
            );

            // then
            assertThat(result).succeedsWithin(TIMEOUT);
            assertThat(activeWhileRunning).hasValue(0);
        }
    }
}
