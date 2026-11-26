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

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.conversion.TestConverter;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.AsyncInMemoryStreamableEventSource;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressContext;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressStrategy;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToClaimTokenException;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.GenericTokenTableFactory;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStoreConfiguration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Test class validating that shutting down a {@link PooledStreamingEventProcessor} whose final progress store hangs
 * ends in time, while no other node processes the released segment before the claim of the shut down node timed out,
 * and the final store landing later does not move the token of the node that took over back.
 * <p>
 * Two processors with the same name, node A and node B, share one JDBC token store on an in-memory HSQLDB database and
 * one event source.
 *
 * @author Mateusz Nowak
 */
class PooledStreamingEventProcessorShutdownJdbcTest {

    private static final String PROCESSOR_NAME = "shutdown-jdbc-processor";
    private static final long CLAIM_EXTENSION_THRESHOLD = 500;
    private static final Duration CLAIM_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AsyncInMemoryStreamableEventSource eventSource = new AsyncInMemoryStreamableEventSource(false, false);
    private final CompletableFuture<Void> finalStoreOfNodeA = new CompletableFuture<>();
    private final CompletableFuture<Throwable> lateStoreOutcome = new CompletableFuture<>();
    private final List<AxonConfiguration> nodes = new ArrayList<>();
    private JDBCDataSource dataSource;
    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JDBCDataSource();
        dataSource.setUrl("jdbc:hsqldb:mem:shutdown-jdbc-test");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        connection = dataSource.getConnection();
        connection.prepareStatement("DROP TABLE IF EXISTS TokenEntry").executeUpdate();
        tokenStore("setup").createSchema(GenericTokenTableFactory.INSTANCE);
    }

    @AfterEach
    void tearDown() throws SQLException {
        finalStoreOfNodeA.complete(null);
        nodes.forEach(AxonConfiguration::shutdown);
        connection.close();
    }

    @Test
    void anotherNodeOnlyTakesOverOnceTheClaimTimedOutAndALateFinalStoreDoesNotMoveItsTokenBack() throws SQLException {
        // given - node A processes the only segment, and its final store will hang until the test lets it land
        PooledStreamingEventProcessor nodeA = startNode("A", true);
        await().atMost(TIMEOUT).until(() -> nodeA.processingStatus().containsKey(0));
        eventSource.publishMessage(EventTestUtils.asEventMessage("first"));
        await().atMost(TIMEOUT).until(() -> positionOf(nodeA).isPresent());
        long positionOfNodeA = positionOf(nodeA).getAsLong();
        PooledStreamingEventProcessor nodeB = startNode("B", false);

        // when
        CompletableFuture<Void> shutdownOfNodeA = nodeA.shutdown();

        // then - the shutdown ends while the final store still hangs, and node A keeps the claim until it timed out
        assertThat(shutdownOfNodeA).succeedsWithin(Duration.ofMillis(CLAIM_EXTENSION_THRESHOLD * 4));
        assertThat(finalStoreOfNodeA).isNotDone();
        await().during(CLAIM_TIMEOUT.dividedBy(2))
               .atMost(TIMEOUT)
               .untilAsserted(() -> assertThat(nodeB.processingStatus()).doesNotContainKey(0));
        assertThat(owner()).isEqualTo("A");
        await().atMost(TIMEOUT).until(() -> nodeB.processingStatus().containsKey(0));

        // when - node B handles the next event, after which the final store of node A lands
        eventSource.publishMessage(EventTestUtils.asEventMessage("second"));
        await().atMost(TIMEOUT).until(() -> positionOf(nodeB).isPresent()
                && positionOf(nodeB).getAsLong() > positionOfNodeA);
        OptionalLong positionOfNodeB = positionOf(nodeB);
        finalStoreOfNodeA.complete(null);

        // then - the token store refuses the late store, so node B keeps the segment at its own position
        assertThat(lateStoreOutcome).succeedsWithin(TIMEOUT)
                                    .isInstanceOf(UnableToClaimTokenException.class);
        await().during(Duration.ofMillis(CLAIM_EXTENSION_THRESHOLD))
               .atMost(TIMEOUT)
               .untilAsserted(() -> assertThat(owner()).isEqualTo("B"));
        assertThat(positionOf(nodeB)).isEqualTo(positionOfNodeB);
    }

    private PooledStreamingEventProcessor startNode(String nodeId, boolean finalStoreHangs) {
        JdbcTokenStore tokenStore = tokenStore(nodeId);
        var module = EventProcessorModule
                .pooledStreaming(PROCESSOR_NAME)
                .eventHandlingComponents(c -> c.autodetected("handler", cfg -> new Handler()))
                .customized((cfg, c) -> c.eventSource(eventSource)
                                         .tokenStore(tokenStore)
                                         .initialSegmentCount(1)
                                         .tokenClaimInterval(100)
                                         .claimExtensionThreshold(CLAIM_EXTENSION_THRESHOLD)
                                         .progressStrategyFactoryBuilder(
                                                 components -> context -> new FinalStoreStrategy(context,
                                                                                                 finalStoreHangs)
                                         ));
        AxonConfiguration configuration = MessagingConfigurer.create()
                                                             .eventProcessing(ep -> ep.pooledStreaming(
                                                                     ps -> ps.processor(module)))
                                                             .build();
        configuration.start();
        nodes.add(configuration);
        return (PooledStreamingEventProcessor) configuration.getComponents(EventProcessor.class).get(PROCESSOR_NAME);
    }

    private JdbcTokenStore tokenStore(String nodeId) {
        JdbcTransactionalExecutorProvider executorProvider = new JdbcTransactionalExecutorProvider(dataSource);
        return new JdbcTokenStore(context -> executorProvider.findTransactionalExecutor(null),
                                  TestConverter.JACKSON.getConverter(),
                                  JdbcTokenStoreConfiguration.DEFAULT.nodeId(nodeId).claimTimeout(CLAIM_TIMEOUT));
    }

    private static OptionalLong positionOf(PooledStreamingEventProcessor node) {
        var status = node.processingStatus().get(0);
        return status == null ? OptionalLong.empty() : status.getCurrentPosition();
    }

    private @Nullable String owner() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner FROM TokenEntry WHERE processorName = ? AND segment = 0")) {
            statement.setString(1, PROCESSOR_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    /**
     * Stores progress on every batch. When the final store hangs, it stores progress only on release, like a strategy
     * that checkpoints, and only once the test completes {@link #finalStoreOfNodeA}, recording how that late store
     * ended.
     */
    private class FinalStoreStrategy implements SegmentProgressStrategy {

        private final SegmentProgressContext context;
        private final boolean finalStoreHangs;

        private FinalStoreStrategy(SegmentProgressContext context, boolean finalStoreHangs) {
            this.context = context;
            this.finalStoreHangs = finalStoreHangs;
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            return finalStoreHangs
                    ? CompletableFuture.completedFuture(null)
                    : context.persistProgress(context.lastConsumedToken(), processingContext);
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }

        @Override
        public CompletableFuture<Void> onSegmentReleased(ProcessingContext processingContext) {
            if (!finalStoreHangs) {
                return context.persistProgress(context.lastConsumedToken(), processingContext);
            }
            return finalStoreOfNodeA.thenCompose(
                    ignored -> context.persistProgress(context.lastConsumedToken(), processingContext)
                                      .whenComplete((result, failure) -> lateStoreOutcome.complete(
                                              failure instanceof CompletionException
                                                      ? failure.getCause() : failure
                                      ))
            );
        }
    }

    private static class Handler {

        @EventHandler
        void on(String event) {
        }
    }
}
