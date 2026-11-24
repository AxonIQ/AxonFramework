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
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.AsyncInMemoryStreamableEventSource;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressContext;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressStrategy;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SimpleSegmentChangeListener;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.axonframework.common.FutureUtils.emptyCompletedFuture;

/**
 * Adversarial probes for the segment release changes of the pooled streaming event processor. Each test asserts the
 * behaviour a user would expect; a failing test demonstrates a hole.
 */
class SegmentReleaseConcurrencyProbeTest {

    private static final String PROCESSOR_NAME = "probe-processor";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long TOKEN_CLAIM_INTERVAL = 50;

    private long tokenClaimInterval = TOKEN_CLAIM_INTERVAL;

    private final List<String> log = new CopyOnWriteArrayList<>();
    private final List<long[]> refreshesOfSegmentOne = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> releaseListenerGate = new CompletableFuture<>();
    private final AtomicBoolean finalStoreNeverCompletes = new AtomicBoolean();
    private final AtomicBoolean lateFinalStore = new AtomicBoolean();
    private final AsyncInMemoryStreamableEventSource eventSource = new AsyncInMemoryStreamableEventSource(false, false);
    private @Nullable AxonConfiguration configuration;
    private volatile @Nullable Runnable handlerHook;

    @AfterEach
    void tearDown() {
        releaseListenerGate.complete(null);
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    /**
     * An instruction given while the processor runs must complete once the processor shut down: the caller has no
     * other way to learn it will never be performed.
     */
    @Test
    void instructionWaitingForAReleaseCompletesWhenTheProcessorShutsDown() {
        // given
        startProcessor(1, TIMEOUT.toMillis(), false);
        processor().releaseSegment(0, TOKEN_CLAIM_INTERVAL, TimeUnit.MILLISECONDS);
        await().atMost(TIMEOUT).until(() -> log.contains("release listener 0"));
        CompletableFuture<Boolean> claim = processor().claimSegment(0);
        await().pollDelay(Duration.ofMillis(TOKEN_CLAIM_INTERVAL * 4)).until(() -> true);

        // when
        CompletableFuture<Void> shutdown = processor().shutdown();
        releaseListenerGate.complete(null);

        // then
        assertThat(shutdown).succeedsWithin(TIMEOUT);
        log.add("shutdown done, claim done=" + claim.isDone());
        await().atMost(TIMEOUT).untilAsserted(
                () -> assertThat(claim).as("claimSegment(0) given before shutdown; log=%s", log).isDone()
        );
    }

    /**
     * A handler that, while its segment is being released, waits for a processor instruction of its own. On main the
     * claim given for the segment being released completes right away, so the handler's instruction runs. With the
     * change, that claim waits for the release, the release waits for the handler's batch, and the handler's
     * instruction waits behind the claim.
     */
    @Test
    void handlerWaitingForAnInstructionWhileItsSegmentIsReleasedIsNotStuck() {
        // given
        CompletableFuture<Void> handlerEntered = new CompletableFuture<>();
        CompletableFuture<Void> handlerGate = new CompletableFuture<>();
        CompletableFuture<String> handlerOutcome = new CompletableFuture<>();
        releaseListenerGate.complete(null);
        startProcessor(1, TIMEOUT.toMillis(), false);
        handlerHook = () -> {
            handlerHook = null;
            handlerEntered.complete(null);
            handlerGate.join();
            try {
                Boolean result = processor().claimSegment(0).get(2, TimeUnit.SECONDS);
                handlerOutcome.complete("instruction completed with " + result);
            } catch (Exception e) {
                handlerOutcome.complete("instruction did not complete: " + e.getClass().getSimpleName());
            }
        };
        eventSource.publishMessage(EventTestUtils.asEventMessage("event"));
        assertThat(handlerEntered).succeedsWithin(TIMEOUT);

        // when - the segment is released while the handler is busy, and a claim for it is given
        processor().releaseSegment(0, TOKEN_CLAIM_INTERVAL, TimeUnit.MILLISECONDS);
        CompletableFuture<Boolean> claim = processor().claimSegment(0);
        await().pollDelay(Duration.ofMillis(TOKEN_CLAIM_INTERVAL * 4)).until(() -> true);
        handlerGate.complete(null);

        // then
        assertThat(handlerOutcome).succeedsWithin(TIMEOUT);
        System.out.println("PROBE handler outcome: " + handlerOutcome.join() + ", outer claim=" + claim);
        assertThat(handlerOutcome.join()).startsWith("instruction completed");
    }

    /**
     * While a split winds down the segment it gives up, the claims of the other segments of this processor must keep
     * being refreshed. The claim timeout of the JDBC and JPA token stores defaults to twice the claim extension
     * threshold (10s versus 5s), so a refresh gap beyond that lets another node steal a segment this node still
     * processes.
     */
    @Test
    void splitWithASlowReleaseListenerKeepsRefreshingTheClaimsOfOtherSegments() {
        assertSplitKeepsRefreshingOtherClaims(false);
    }

    @Test
    void splitWithASlowReleaseListenerAndSlowFinalStoreKeepsRefreshingTheClaimsOfOtherSegments() {
        assertSplitKeepsRefreshingOtherClaims(true);
    }

    private void assertSplitKeepsRefreshingOtherClaims(boolean slowFinalStore) {
        // given - the default ratio of the claim interval and threshold (5s each), so the coordinator runs every
        // threshold / 2 when idle
        long threshold = 500;
        tokenClaimInterval = threshold;
        finalStoreNeverCompletes.set(slowFinalStore);
        startProcessor(2, threshold, false);
        await().atMost(TIMEOUT).until(() -> processor().processingStatus().containsKey(1));
        await().pollDelay(Duration.ofMillis(threshold * 3)).until(() -> true);
        // split when the claim of segment one is about due for a refresh
        await().pollInterval(Duration.ofMillis(5)).atMost(TIMEOUT).until(() -> {
            long last = refreshesOfSegmentOne.getLast()[0];
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - last) >= threshold * Long.getLong("probe.agePercent", 90) / 100;
        });
        long splitStart = System.nanoTime();

        // when - the release listener of segment zero never completes, so it times out after the threshold
        CompletableFuture<Boolean> split = processor().splitSegment(0);
        assertThat(split).succeedsWithin(Duration.ofSeconds(10)).isEqualTo(true);
        long splitEnd = System.nanoTime();
        await().pollDelay(Duration.ofMillis(threshold * 2)).until(() -> true);

        // then
        List<Long> times = new ArrayList<>();
        refreshesOfSegmentOne.forEach(r -> times.add(r[0]));
        long maxGapMs = 0;
        long gapStartMs = 0;
        for (int i = 1; i < times.size(); i++) {
            long gap = TimeUnit.NANOSECONDS.toMillis(times.get(i) - times.get(i - 1));
            if (times.get(i) >= splitStart && gap > maxGapMs) {
                maxGapMs = gap;
                gapStartMs = TimeUnit.NANOSECONDS.toMillis(times.get(i - 1) - splitStart);
            }
        }
        long splitMs = TimeUnit.NANOSECONDS.toMillis(splitEnd - splitStart);
        System.out.printf("PROBE refresh gap of segment 1: max=%dms (starting %dms relative to split start), "
                                  + "split took %dms, threshold=%dms, refreshes=%d%n",
                          maxGapMs, gapStartMs, splitMs, threshold, times.size());
        assertThat(maxGapMs)
                .as("max gap between claim refreshes of segment 1 around a split of segment 0 (split took %dms)",
                    splitMs)
                .isLessThan(2 * threshold);
    }

    /**
     * A final store that exceeds the claim extension threshold counts as failed and the claim is released. The store
     * itself is not cancelled, though: it is applied after the claim was released.
     */
    @Test
    void finalStoreThatTimedOutIsNotAppliedAfterTheClaimIsReleased() {
        // given
        long threshold = 300;
        lateFinalStore.set(true);
        releaseListenerGate.complete(null);
        startProcessor(1, threshold, true);
        eventSource.publishMessage(EventTestUtils.asEventMessage("event"));
        await().atMost(TIMEOUT).until(() -> processor().processingStatus().get(0).getCurrentPosition().isPresent());

        // when
        processor().releaseSegment(0, 60, TimeUnit.SECONDS);

        // then
        await().pollDelay(Duration.ofMillis(threshold * 5)).until(() -> true);
        System.out.println("PROBE late store log: " + log);
        int released = log.indexOf("claim released 0");
        assertThat(released).as("log=%s", log).isNotNegative();
        assertThat(log.subList(released, log.size()))
                .as("token store calls after the claim of segment 0 was released; log=%s", log)
                .noneMatch(entry -> entry.startsWith("store token 0"));
    }

    private void startProcessor(int segmentCount, long claimExtensionThreshold, boolean deferredStore) {
        var listener = new SimpleSegmentChangeListener(
                (segment, from) -> emptyCompletedFuture(),
                segment -> {
                    log.add("release listener " + segment.getSegmentId());
                    return segment.getSegmentId() == 0 ? releaseListenerGate : emptyCompletedFuture();
                }
        );
        var module = EventProcessorModule.pooledStreaming(PROCESSOR_NAME)
                                         .eventHandlingComponents(c -> c.autodetected("handler",
                                                                                      cfg -> new Handler()))
                                         .customized((cfg, c) -> c.eventSource(eventSource)
                                                                  .tokenStore(new RecordingTokenStore())
                                                                  .initialSegmentCount(segmentCount)
                                                                  .tokenClaimInterval(tokenClaimInterval)
                                                                  .claimExtensionThreshold(claimExtensionThreshold)
                                                                  .progressStrategyFactoryBuilder(
                                                                          components -> ctx -> new ProbeStrategy(
                                                                                  ctx, deferredStore,
                                                                                  claimExtensionThreshold)
                                                                  )
                                                                  .addSegmentChangeListener(listener));
        configuration = MessagingConfigurer.create()
                                           .eventProcessing(ep -> ep.pooledStreaming(ps -> ps.processor(module)))
                                           .build();
        configuration.start();
        await().atMost(TIMEOUT).until(() -> processor().processingStatus().containsKey(0));
    }

    private PooledStreamingEventProcessor processor() {
        return (PooledStreamingEventProcessor) configuration.getComponents(EventProcessor.class).get(PROCESSOR_NAME);
    }

    /**
     * Stores per batch unless {@code deferred}; a deferred strategy only stores on release, which is the self
     * checkpointing case.
     */
    private class ProbeStrategy implements SegmentProgressStrategy {

        private final SegmentProgressContext context;
        private final boolean deferred;
        private final long threshold;

        private ProbeStrategy(SegmentProgressContext context, boolean deferred, long threshold) {
            this.context = context;
            this.deferred = deferred;
            this.threshold = threshold;
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            return deferred
                    ? emptyCompletedFuture()
                    : context.persistProgress(context.lastConsumedToken(), processingContext);
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }

        @Override
        public CompletableFuture<Void> onSegmentReleased(ProcessingContext processingContext) {
            int segmentId = context.segment().getSegmentId();
            if (segmentId == 0 && finalStoreNeverCompletes.get()) {
                return new CompletableFuture<>();
            }
            if (lateFinalStore.get()) {
                return CompletableFuture.runAsync(
                        () -> {
                        },
                        CompletableFuture.delayedExecutor(threshold * 3, TimeUnit.MILLISECONDS)
                ).thenCompose(ignored -> {
                    log.add("late final store runs " + segmentId);
                    return context.persistProgress(context.lastConsumedToken(), processingContext);
                });
            }
            return context.persistProgress(context.lastConsumedToken(), processingContext);
        }
    }

    private class RecordingTokenStore extends InMemoryTokenStore {

        @Override
        public CompletableFuture<Void> storeToken(@Nullable TrackingToken token,
                                                  String processorName,
                                                  int segmentId,
                                                  ProcessingContext context) {
            log.add("store token " + segmentId + " " + token);
            if (segmentId == 1) {
                refreshesOfSegmentOne.add(new long[]{System.nanoTime()});
            }
            return super.storeToken(token, processorName, segmentId, context);
        }

        @Override
        public CompletableFuture<Void> extendClaim(String processorName, int segmentId, ProcessingContext context) {
            if (segmentId == 1) {
                refreshesOfSegmentOne.add(new long[]{System.nanoTime()});
            }
            return super.extendClaim(processorName, segmentId, context);
        }

        @Override
        public CompletableFuture<TrackingToken> fetchToken(String processorName,
                                                           int segmentId,
                                                           ProcessingContext context) {
            if (segmentId == 1) {
                refreshesOfSegmentOne.add(new long[]{System.nanoTime()});
            }
            return super.fetchToken(processorName, segmentId, context);
        }

        @Override
        public CompletableFuture<Void> releaseClaim(String processorName,
                                                    int segment,
                                                    @Nullable ProcessingContext context) {
            log.add("claim released " + segment);
            return super.releaseClaim(processorName, segment, context);
        }
    }

    private class Handler {

        @EventHandler
        void on(String event) {
            Runnable hook = handlerHook;
            if (hook != null) {
                hook.run();
            }
        }
    }
}
