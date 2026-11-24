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

package org.axonframework.messaging.eventhandling.processing.streaming.pooled;

import org.axonframework.common.jdbc.ConnectionExecutor;
import org.axonframework.conversion.TestConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.GenericTokenTableFactory;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStoreConfiguration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;

/**
 * Probes what a final store that the processor stopped waiting for does when it still lands after the claim was
 * released, against the JDBC token store.
 */
class LateStoreAfterReleaseJdbcProbeTest {

    private static final String PROCESSOR = "probe";

    private Connection connection;
    private JdbcTokenStore nodeA;
    private JdbcTokenStore nodeB;

    @BeforeEach
    void setUp() throws SQLException {
        JDBCDataSource dataSource = new JDBCDataSource();
        dataSource.setUrl("jdbc:hsqldb:mem:latestoreprobe");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        connection = dataSource.getConnection();
        connection.setAutoCommit(true);
        connection.prepareStatement("DROP TABLE IF EXISTS TokenEntry").executeUpdate();
        var provider = new JdbcTransactionalExecutorProvider(dataSource);
        nodeA = new JdbcTokenStore(provider, TestConverter.JACKSON.getConverter(),
                                   JdbcTokenStoreConfiguration.DEFAULT.nodeId("A")
                                                                      .claimTimeout(Duration.ofSeconds(10)));
        nodeB = new JdbcTokenStore(provider, TestConverter.JACKSON.getConverter(),
                                   JdbcTokenStoreConfiguration.DEFAULT.nodeId("B")
                                                                      .claimTimeout(Duration.ofSeconds(10)));
        nodeA.createSchema(GenericTokenTableFactory.INSTANCE);
        joinAndUnwrap(nodeA.initializeTokenSegments(PROCESSOR, 1, null, ctx()));
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    @Test
    void lateStoreAfterReleaseDoesNotReclaimTheSegment() throws SQLException {
        // given - node A processed the segment and released its claim
        joinAndUnwrap(nodeA.fetchToken(PROCESSOR, 0, ctx()));
        joinAndUnwrap(nodeA.releaseClaim(PROCESSOR, 0, ctx()));
        String ownerAfterRelease = owner();

        // when - the final store node A stopped waiting for lands now
        joinAndUnwrap(nodeA.storeToken(new GlobalSequenceTrackingToken(5), PROCESSOR, 0, ctx()));

        // then
        System.out.printf("PROBE owner after release=%s, owner after late store=%s%n", ownerAfterRelease, owner());
        assertThat(owner()).as("owner of segment 0 after node A's late store").isNull();
    }

    @Test
    void lateStoreDoesNotMoveTheTokenBackBehindAnotherNode() throws SQLException {
        // given - node A released, node B claimed, advanced to 10 and released again
        joinAndUnwrap(nodeA.fetchToken(PROCESSOR, 0, ctx()));
        joinAndUnwrap(nodeA.releaseClaim(PROCESSOR, 0, ctx()));
        joinAndUnwrap(nodeB.fetchToken(PROCESSOR, 0, ctx()));
        joinAndUnwrap(nodeB.storeToken(new GlobalSequenceTrackingToken(10), PROCESSOR, 0, ctx()));
        joinAndUnwrap(nodeB.releaseClaim(PROCESSOR, 0, ctx()));

        // when - node A's late final store of 5 lands
        joinAndUnwrap(nodeA.storeToken(new GlobalSequenceTrackingToken(5), PROCESSOR, 0, ctx()));

        // then
        var token = joinAndUnwrap(nodeA.fetchToken(PROCESSOR, 0, ctx()));
        System.out.printf("PROBE token after B stored 10 and A's late store of 5: %s, owner=%s%n", token, owner());
        assertThat(token).isEqualTo(new GlobalSequenceTrackingToken(10));
    }

    private String owner() throws SQLException {
        try (ResultSet rs = connection.prepareStatement("SELECT owner FROM TokenEntry WHERE segment = 0")
                                      .executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private ProcessingContext ctx() {
        return new StubProcessingContext()
                .withResource(JdbcTransactionalExecutorProvider.SUPPLIER_KEY,
                              () -> new ConnectionExecutor(() -> connection));
    }
}
