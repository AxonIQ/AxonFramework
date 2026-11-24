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
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.AsyncInMemoryStreamableEventSource;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SimpleSegmentChangeListener;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.GenericTokenTableFactory;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jdbc.JdbcTokenStoreConfiguration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;

/**
 * End-to-end probe for PR 5318: two pooled streaming event processors with the same name ("node A" and "node B")
 * share one JDBC token store (HSQLDB, real claims per node id) and one event source. Node A releases its only segment
 * while its release listener winds down for a while. The handler of each node records every event it handles and
 * whether the token store said that node owned the segment at that moment.
 * <p>
 * Counted per round: events handled by both nodes (cross-node duplicates), events handled while the token store did not
 * name the handling node as owner (handled without claim), claims node A's listener saw while its own release of the
 * segment was still running (reclaimed mid-release), and events never handled (gaps).
 * <p>
 * The same file runs unchanged on main and on the PR branch.
 */
class ReleaseReclaimEndToEndProbeTest {

    private static final String PROCESSOR = "e2e";
    private static final long CLAIM_INTERVAL = 100;
    private static final AtomicInteger DB = new AtomicInteger();

    @Test
    void slowReleaseListenerShortReleaseDuration() throws Exception {
        runScenario("S1 listener=600ms release=50ms threshold=5000ms", 600, 50, 5000, 5);
    }

    @Test
    void defaultsInstantListenerImmediateRelease() throws Exception {
        runScenario("S2 listener=0ms release=-1ms threshold=5000ms", 0, -1, 5000, 20);
    }

    @Test
    void listenerExceedsClaimExtensionThreshold() throws Exception {
        runScenario("S3 listener=1500ms release=50ms threshold=500ms", 1500, 50, 500, 5);
    }

    @Test
    void slowReleaseListenerWhileTheStreamIsIdle() throws Exception {
        runScenario("S4 idle listener=600ms release=50ms threshold=5000ms", 600, 50, 5000, 5, true);
    }

    @Test
    void defaultsWhileTheStreamIsIdle() throws Exception {
        runScenario("S5 idle listener=0ms release=-1ms threshold=5000ms", 0, -1, 5000, 20, true);
    }

    private void runScenario(String name, long listenerDelay, long releaseMs, long threshold, int rounds)
            throws Exception {
        runScenario(name, listenerDelay, releaseMs, threshold, rounds, false);
    }

    private void runScenario(String name, long listenerDelay, long releaseMs, long threshold, int rounds,
                             boolean idleDuringRelease)
            throws Exception {
        int totalStateLost = 0;
        int totalDup = 0, totalNoClaim = 0, totalMidRelease = 0, totalGaps = 0, totalPublished = 0;
        long totalHandled = 0;
        for (int round = 0; round < rounds; round++) {
            Result r = round(listenerDelay, releaseMs, threshold, idleDuringRelease);
            System.out.printf("PROBE %s round=%d published=%d handled=%d crossNodeDuplicates=%d "
                                      + "handledWithoutClaim(A=%d,B=%d) reclaimedMidRelease=%d gaps=%d handledByA=%d handledByB=%d "
                                      + "processingWithListenerStateDropped=%d%n",
                              name, round, r.published, r.handled, r.crossNodeDuplicates, r.noClaimA, r.noClaimB,
                              r.reclaimedMidRelease, r.gaps, r.byA, r.byB, r.stateLost);
            totalDup += r.crossNodeDuplicates;
            totalNoClaim += r.noClaimA + r.noClaimB;
            totalMidRelease += r.reclaimedMidRelease;
            totalGaps += r.gaps;
            totalStateLost += r.stateLost;
            totalPublished += r.published;
            totalHandled += r.handled;
        }
        System.out.printf("PROBE-TOTAL %s rounds=%d published=%d handled=%d crossNodeDuplicates=%d "
                                  + "handledWithoutClaim=%d reclaimedMidRelease=%d gaps=%d processingWithListenerStateDropped=%d%n",
                          name, rounds, totalPublished, totalHandled, totalDup, totalNoClaim, totalMidRelease,
                          totalGaps, totalStateLost);
    }

    private Result round(long listenerDelay, long releaseMs, long threshold, boolean idleDuringRelease)
            throws Exception {
        JDBCDataSource ds = new JDBCDataSource();
        ds.setUrl("jdbc:hsqldb:mem:e2e" + DB.incrementAndGet());
        ds.setUser("sa");
        ds.setPassword("");
        Connection sentinel = ds.getConnection();
        JdbcTransactionalExecutorProvider base = new JdbcTransactionalExecutorProvider(ds);
        JdbcTokenStore a = store(base, "A");
        JdbcTokenStore b = store(base, "B");
        a.createSchema(GenericTokenTableFactory.INSTANCE);

        AsyncInMemoryStreamableEventSource source = new AsyncInMemoryStreamableEventSource(false, false);
        Map<Integer, Set<String>> handledBy = new ConcurrentHashMap<>();
        AtomicInteger noClaimA = new AtomicInteger();
        AtomicInteger noClaimB = new AtomicInteger();
        AtomicInteger reclaimedMidRelease = new AtomicInteger();
        AtomicBoolean aReleasing = new AtomicBoolean();

        AtomicBoolean loadedA = new AtomicBoolean();
        AtomicBoolean loadedB = new AtomicBoolean();
        AxonConfiguration nodeA = start("A", source, a, threshold, ds, handledBy, noClaimA,
                                        listenerDelay, aReleasing, reclaimedMidRelease, loadedA);
        await().atMost(Duration.ofSeconds(5)).until(() -> processor(nodeA).processingStatus().containsKey(0));
        AxonConfiguration nodeB = start("B", source, b, threshold, ds, handledBy, noClaimB,
                                        0, new AtomicBoolean(), new AtomicInteger(), loadedB);

        AtomicInteger published = new AtomicInteger();
        AtomicBoolean publishing = new AtomicBoolean(true);
        AtomicBoolean paused = new AtomicBoolean(false);
        Thread publisher = new Thread(() -> {
            while (publishing.get()) {
                if (paused.get()) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                source.publishMessage(EventTestUtils.asEventMessage("e-" + published.getAndIncrement()));
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        publisher.start();
        Thread.sleep(300);

        if (idleDuringRelease) {
            paused.set(true);
            Thread.sleep(200);
        }
        processor(nodeA).releaseSegment(0, releaseMs, TimeUnit.MILLISECONDS);
        if (idleDuringRelease) {
            Thread.sleep(listenerDelay + 1000);
            paused.set(false);
        }
        Thread.sleep(Math.max(2500, listenerDelay * 3));
        publishing.set(false);
        publisher.join();

        int total = published.get();
        try {
            await().atMost(Duration.ofSeconds(15)).until(() -> handledBy.size() >= total);
        } catch (Exception ignored) {
            // gaps are counted below
        }
        // A node processing the segment while its listener dropped the segment's state (as the workflow engine's
        // listener drops the workflows of a released segment).
        int stateLost = (processor(nodeA).processingStatus().containsKey(0) && !loadedA.get() ? 1 : 0)
                + (processor(nodeB).processingStatus().containsKey(0) && !loadedB.get() ? 1 : 0);
        nodeA.shutdown();
        nodeB.shutdown();
        sentinel.close();

        int dup = (int) handledBy.values().stream().filter(nodes -> nodes.size() > 1).count();
        int gaps = 0;
        for (int i = 0; i < total; i++) {
            if (!handledBy.containsKey(i)) {
                gaps++;
            }
        }
        int byA = (int) handledBy.values().stream().filter(nodes -> nodes.contains("A")).count();
        int byB = (int) handledBy.values().stream().filter(nodes -> nodes.contains("B")).count();
        return new Result(total, handledBy.size(), dup, noClaimA.get(), noClaimB.get(), reclaimedMidRelease.get(),
                          gaps, byA, byB, stateLost);
    }

    private static JdbcTokenStore store(JdbcTransactionalExecutorProvider base, String node) {
        // Each token operation runs in its own committed transaction, whatever processing context is given.
        return new JdbcTokenStore(pc -> base.findTransactionalExecutor(null),
                                  TestConverter.JACKSON.getConverter(),
                                  JdbcTokenStoreConfiguration.DEFAULT.nodeId(node));
    }

    private static AxonConfiguration start(String node,
                                           AsyncInMemoryStreamableEventSource source,
                                           JdbcTokenStore store,
                                           long threshold,
                                           JDBCDataSource ds,
                                           Map<Integer, Set<String>> handledBy,
                                           AtomicInteger noClaim,
                                           long listenerDelay,
                                           AtomicBoolean releasing,
                                           AtomicInteger reclaimedMidRelease,
                                           AtomicBoolean loaded) {
        var listener = new SimpleSegmentChangeListener(
                (segment, from) -> {
                    if (releasing.get()) {
                        reclaimedMidRelease.incrementAndGet();
                    }
                    loaded.set(true);
                    return CompletableFuture.completedFuture(null);
                },
                segment -> {
                    releasing.set(true);
                    return CompletableFuture.runAsync(() -> {
                    }, CompletableFuture.delayedExecutor(listenerDelay, TimeUnit.MILLISECONDS))
                                            .whenComplete((r, t) -> {
                                                loaded.set(false);
                                                releasing.set(false);
                                            });
                }
        );
        var module = EventProcessorModule.pooledStreaming(PROCESSOR)
                                         .eventHandlingComponents(c -> c.autodetected(
                                                 "handler", cfg -> new Handler(node, ds, handledBy, noClaim)))
                                         .customized((cfg, c) -> c.eventSource(source)
                                                                  .tokenStore(store)
                                                                  .initialSegmentCount(1)
                                                                  .tokenClaimInterval(CLAIM_INTERVAL)
                                                                  .claimExtensionThreshold(threshold)
                                                                  .addSegmentChangeListener(listener));
        AxonConfiguration configuration = MessagingConfigurer.create()
                                                             .eventProcessing(ep -> ep.pooledStreaming(
                                                                     ps -> ps.processor(module)))
                                                             .build();
        configuration.start();
        return configuration;
    }

    private static PooledStreamingEventProcessor processor(AxonConfiguration configuration) {
        return (PooledStreamingEventProcessor) configuration.getComponents(EventProcessor.class).get(PROCESSOR);
    }

    private record Result(int published, int handled, int crossNodeDuplicates, int noClaimA, int noClaimB,
                          int reclaimedMidRelease, int gaps, int byA, int byB, int stateLost) {

    }

    static class Handler {

        private final String node;
        private final JDBCDataSource ds;
        private final Map<Integer, Set<String>> handledBy;
        private final AtomicInteger noClaim;

        Handler(String node, JDBCDataSource ds, Map<Integer, Set<String>> handledBy, AtomicInteger noClaim) {
            this.node = node;
            this.ds = ds;
            this.handledBy = handledBy;
            this.noClaim = noClaim;
        }

        @EventHandler
        void on(String event) throws Exception {
            int seq = Integer.parseInt(event.substring(2));
            handledBy.computeIfAbsent(seq, k -> ConcurrentHashMap.newKeySet()).add(node);
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT owner FROM TokenEntry WHERE processorName = ? AND segment = 0")) {
                ps.setString(1, PROCESSOR);
                try (ResultSet rs = ps.executeQuery()) {
                    String owner = rs.next() ? rs.getString(1) : null;
                    if (!node.equals(owner)) {
                        noClaim.incrementAndGet();
                    }
                }
            }
        }
    }
}
