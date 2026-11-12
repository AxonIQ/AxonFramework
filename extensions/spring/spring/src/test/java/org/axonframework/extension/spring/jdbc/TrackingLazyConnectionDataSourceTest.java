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

package org.axonframework.extension.spring.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.axonframework.common.jdbc.PhysicalConnectionAware;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link TrackingLazyConnectionDataSource}.
 *
 * @author John Hendrikx
 */
@Timeout(60)
class TrackingLazyConnectionDataSourceTest {

    private HikariDataSource pool;
    private TrackingLazyConnectionDataSource testSubject;
    private ExecutorService threads;

    @BeforeEach
    void beforeEach() {
        HikariConfig config = new HikariConfig();

        config.setJdbcUrl("jdbc:hsqldb:mem:" + UUID.randomUUID());
        config.setUsername("SA");
        config.setPassword("");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(2000);

        pool = new HikariDataSource(config);
        testSubject = new TrackingLazyConnectionDataSource(pool);
        threads = Executors.newCachedThreadPool();
    }

    @AfterEach
    void afterEach() {
        threads.shutdownNow();
        pool.close();
    }

    private int connectionsInUse() {
        return pool.getHikariPoolMXBean().getActiveConnections();
    }

    private static boolean physical(Connection connection) {
        return ((PhysicalConnectionAware) connection).isPhysicallyConnected();
    }

    private static void runStatement(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("VALUES 1");
        }
    }

    @Nested
    class PhysicalState {

        @Test
        void shouldNotTakeAPhysicalConnectionForTransactionDemarcation() throws Exception {
            // given
            Connection connection = testSubject.getConnection();

            // when
            connection.setAutoCommit(false);
            connection.setReadOnly(false);
            connection.commit();
            connection.rollback();

            // then
            assertThat(physical(connection)).isFalse();
            assertThat(connectionsInUse()).isZero();

            connection.close();
        }

        @Test
        void shouldTakeAPhysicalConnectionWhenAStatementIsCreated() throws Exception {
            // given
            Connection connection = testSubject.getConnection();

            // when
            connection.setAutoCommit(false);
            runStatement(connection);

            // then
            assertThat(physical(connection)).isTrue();
            assertThat(connectionsInUse()).isEqualTo(1);

            connection.close();
        }

        @Test
        void shouldTakeAPhysicalConnectionWhenTheTargetConnectionIsRequested() throws Exception {
            // given
            Connection connection = testSubject.getConnection();

            // when
            ((ConnectionProxy) connection).getTargetConnection();

            // then
            assertThat(physical(connection)).isTrue();

            connection.close();
        }

        @Test
        void shouldTrackConnectionsIndependentlyOfEachOther() throws Exception {
            // given
            Connection first = testSubject.getConnection();
            Connection second = testSubject.getConnection();

            // when
            runStatement(first);

            // then
            assertThat(physical(first)).isTrue();
            assertThat(physical(second)).isFalse();
            assertThat(connectionsInUse()).isEqualTo(1);

            first.close();
            second.close();
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void shouldNotNeedAPhysicalConnectionToCloseAConnectionThatWasNeverUsed() throws Exception {
            // given
            Connection connection = testSubject.getConnection();

            // when
            connection.close();

            // then
            assertThat(connectionsInUse()).isZero();
        }

        @Test
        void shouldReturnThePhysicalConnectionToThePoolWhenClosed() throws Exception {
            // given
            Connection connection = testSubject.getConnection();

            runStatement(connection);

            // when
            connection.close();

            // then
            assertThat(connectionsInUse()).isZero();
        }

        @Test
        void shouldNotTrackConnectionsRequestedWithCredentials() throws Exception {
            // given
            DriverManagerDataSource target = new DriverManagerDataSource(pool.getJdbcUrl(), "SA", "");
            TrackingLazyConnectionDataSource withCredentials = new TrackingLazyConnectionDataSource(target);

            // when
            Connection connection = withCredentials.getConnection("SA", "");

            // then
            assertThat(connection).isNotInstanceOf(PhysicalConnectionAware.class);

            connection.close();
        }
    }

    @Nested
    class Concurrency {

        @Test
        void shouldTakeOnlyOnePhysicalConnectionWhenThreadsUseAConnectionForTheFirstTimeTogether() throws Exception {
            // given
            Connection connection = testSubject.getConnection();
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < 8; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        runStatement(connection);
                    }
                    catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, threads));
            }

            // when
            go.countDown();
            futures.forEach(CompletableFuture::join);

            // then
            assertThat(connectionsInUse()).isEqualTo(1);

            connection.close();
        }

        @Test
        void shouldKeepAPhysicalConnectionFromBeingTakenWhileADecisionIsRunning() throws Exception {
            // given
            Connection connection = testSubject.getConnection();
            PhysicalConnectionAware aware = (PhysicalConnectionAware) connection;
            CountDownLatch deciding = new CountDownLatch(1);
            AtomicBoolean decisionFinished = new AtomicBoolean();
            AtomicBoolean statementCreatedWhileDeciding = new AtomicBoolean();

            CompletableFuture<Void> decision = CompletableFuture.runAsync(
                () -> aware.withPhysicalConnectionShielded(physical -> {
                    deciding.countDown();

                    sleep(500);
                    decisionFinished.set(true);

                    return physical;
                }), threads
            );

            assertThat(deciding.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            CompletableFuture<Void> user = CompletableFuture.runAsync(() -> {
                try (Statement statement = connection.createStatement()) {
                    statementCreatedWhileDeciding.set(!decisionFinished.get());
                }
                catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }, threads);

            decision.join();
            user.join();

            // then
            assertThat(statementCreatedWhileDeciding).isFalse();
            assertThat(aware.isPhysicallyConnected()).isTrue();

            connection.close();
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
