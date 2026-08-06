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

package org.axonframework.hunt.scenario;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressContext;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressStrategy;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whether split and merge inherit the progress a released segment flushed, on a token store that only applies stores
 * when the surrounding transaction commits.
 * <p>
 * A split or merge aborts the local work package, lets its strategy persist final progress (the release flush), and
 * then reads the segment's token to seed the segment(s) it produces. With a commit-on-flush store, a read sharing the
 * flush's transaction would not observe the flush: the produced segments would inherit the stale stored token and
 * redeliver everything between it and the real progress. The framework commits the flush in its own transaction
 * before reading -- these probes verify that on the framework's own commit-buffered in-heap store, which applies
 * {@code storeToken} only on commit of the given context.
 * <p>
 * The third probe fails the read that follows the committed flush -- the crash window between the two transactions --
 * and checks what survives: the flushed progress must be durable, the store must not hold a half-split topology, and
 * the segment must be claimable again. That is the "rewinds at most one batch" handover contract: here the release
 * flush covered everything, so the rewind must be zero.
 */
class SplitMergeFlushedProgressInheritanceTest {

    private static final String PROCESSOR = "flush-inheritance-probe";
    private static final long STALE_POSITION = 100L;
    private static final long FLUSHED_POSITION = 700L;
    private static final long MERGE_FLUSHED_POSITION = 900L;
    private static final long BEAT = 100L;

    private static TokenStore initializedStore(TokenStore tokenStore) {
        tokenStore.initializeTokenSegments(PROCESSOR, 1, new GlobalSequenceTrackingToken(STALE_POSITION), null)
                  .orTimeout(30, TimeUnit.SECONDS)
                  .join();
        return tokenStore;
    }

    private static long position(TokenStore tokenStore, int segmentId) {
        return ProgressProbeSupport.storedPosition(tokenStore, PROCESSOR, segmentId);
    }

    private static void awaitClaims(ProgressProbeSupport.Harness harness, int count) {
        Awaitility.await(count + " claimed segment(s)")
                  .atMost(Duration.ofSeconds(30))
                  .pollInterval(Duration.ofMillis(25))
                  .untilAsserted(() -> assertThat(harness.processor().processingStatus()).hasSize(count));
    }

    @Nested
    class WhenTheReleaseFlushCommits {

        @Test
        void bothSplitHalvesInheritTheFlushedPositionNotTheStaleStoredToken() {
            // given a store whose durable position is stale, and a strategy holding real progress it only flushes
            // when the segment is released
            TokenStore tokenStore = initializedStore(new InMemoryTokenStore());
            FlushOnReleaseStrategy.PROGRESS.set(FLUSHED_POSITION);
            try (ProgressProbeSupport.Harness harness =
                         ProgressProbeSupport.Harness.start(PROCESSOR, tokenStore, FlushOnReleaseStrategy::new, BEAT)) {
                awaitClaims(harness, 1);

                // when the claimed segment is split
                Boolean split = harness.processor().splitSegment(0).orTimeout(30, TimeUnit.SECONDS).join();

                // then both halves start from the flushed position, not from the stale stored token
                assertThat(split).isTrue();
                assertThat(position(tokenStore, 0))
                        .as("the lower half inherits the release-flushed position")
                        .isEqualTo(FLUSHED_POSITION);
                assertThat(position(tokenStore, 1))
                        .as("the upper half inherits the release-flushed position")
                        .isEqualTo(FLUSHED_POSITION);
            }
        }

        @Test
        void theMergedSegmentInheritsTheFlushedPositionsOfBothHalves() {
            // given a split topology whose two halves are claimed, and strategies that flush fresh progress on release
            TokenStore tokenStore = initializedStore(new InMemoryTokenStore());
            FlushOnReleaseStrategy.PROGRESS.set(FLUSHED_POSITION);
            try (ProgressProbeSupport.Harness harness =
                         ProgressProbeSupport.Harness.start(PROCESSOR, tokenStore, FlushOnReleaseStrategy::new, BEAT)) {
                awaitClaims(harness, 1);
                assertThat(harness.processor().splitSegment(0).orTimeout(30, TimeUnit.SECONDS).join()).isTrue();
                awaitClaims(harness, 2);

                // when progress moves on and the halves are merged
                FlushOnReleaseStrategy.PROGRESS.set(MERGE_FLUSHED_POSITION);
                Boolean merged = harness.processor().mergeSegment(0).orTimeout(30, TimeUnit.SECONDS).join();

                // then the merged segment starts from the freshly flushed position of both halves
                assertThat(merged).isTrue();
                assertThat(position(tokenStore, 0))
                        .as("the merged segment inherits the release-flushed positions")
                        .isEqualTo(MERGE_FLUSHED_POSITION);
                List<Segment> segments = tokenStore.fetchSegments(PROCESSOR, null)
                                                   .orTimeout(30, TimeUnit.SECONDS)
                                                   .join();
                assertThat(segments).hasSize(1);
            }
        }
    }

    @Nested
    class WhenTheReadAfterTheCommittedFlushFails {

        @Test
        void theFlushedProgressSurvivesAndTheSegmentIsClaimedAgain() {
            // given a store that fails the first read of the segment's token arriving after the release flush ran --
            // the crash window between the flush's transaction and the read's
            FailFetchAfterReleaseTokenStore tokenStore =
                    new FailFetchAfterReleaseTokenStore(new InMemoryTokenStore());
            initializedStore(tokenStore);
            FlushOnReleaseStrategy.PROGRESS.set(FLUSHED_POSITION);
            FlushOnReleaseStrategy.RELEASED = tokenStore.released();
            try {
                try (ProgressProbeSupport.Harness harness =
                             ProgressProbeSupport.Harness.start(PROCESSOR,
                                                                tokenStore,
                                                                FlushOnReleaseStrategy::new,
                                                                BEAT)) {
                    awaitClaims(harness, 1);
                    tokenStore.arm();

                    // when the split's read fails between the two transactions
                    assertThatThrownBy(() -> harness.processor().splitSegment(0)
                                                    .orTimeout(30, TimeUnit.SECONDS)
                                                    .join())
                            .as("the split fails when its post-flush read fails")
                            .hasRootCauseInstanceOf(SimulatedStoreFailure.class);

                    // then the committed flush is durable: no progress was lost, so the handover rewinds zero batches
                    assertThat(position(tokenStore, 0))
                            .as("the release-flushed position survives the failed split")
                            .isEqualTo(FLUSHED_POSITION);
                    // and the topology is intact: no half-split segment layout was left behind
                    List<Segment> segments = tokenStore.fetchSegments(PROCESSOR, null)
                                                       .orTimeout(30, TimeUnit.SECONDS)
                                                       .join();
                    assertThat(segments).hasSize(1);
                    // and the segment is claimable again: the coordinator re-claims it and reports it healthy
                    awaitClaims(harness, 1);
                    assertThat(harness.processor().processingStatus().values())
                            .noneMatch(status -> status.isErrorState());
                }
            } finally {
                FlushOnReleaseStrategy.RELEASED = null;
            }
        }
    }

    /**
     * Holds the segment's real progress in memory and persists it only when the segment is released -- the shape that
     * makes stale-token inheritance visible: nothing else ever stores.
     */
    private static final class FlushOnReleaseStrategy implements SegmentProgressStrategy {

        private static final AtomicLong PROGRESS = new AtomicLong();
        private static volatile @Nullable AtomicBoolean RELEASED;

        private final SegmentProgressContext context;

        private FlushOnReleaseStrategy(SegmentProgressContext context) {
            this.context = context;
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            // Nothing is stored during processing; the release flush is the only store.
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> onSegmentReleased(ProcessingContext processingContext) {
            AtomicBoolean released = RELEASED;
            if (released != null) {
                released.set(true);
            }
            return context.persistProgress(new GlobalSequenceTrackingToken(PROGRESS.get()), processingContext);
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }
    }

    private static final class SimulatedStoreFailure extends RuntimeException {

        private SimulatedStoreFailure() {
            super("Simulated store failure between the release flush's transaction and the read's.");
        }
    }

    /**
     * Fails the first {@code fetchToken} of segment 0 that arrives once armed and after the release flush ran. During
     * a split the segment is blocked from coordinator claims, so that read is exactly the one following the committed
     * flush.
     */
    private static final class FailFetchAfterReleaseTokenStore extends ForwardingTokenStore {

        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private FailFetchAfterReleaseTokenStore(TokenStore delegate) {
            super(delegate);
        }

        void arm() {
            armed.set(true);
        }

        AtomicBoolean released() {
            return released;
        }

        @Override
        public CompletableFuture<TrackingToken> fetchToken(String processorName,
                                                           int segmentId,
                                                           @Nullable ProcessingContext context) {
            if (segmentId == 0 && released.get() && armed.compareAndSet(true, false)) {
                return CompletableFuture.failedFuture(new SimulatedStoreFailure());
            }
            return super.fetchToken(processorName, segmentId, context);
        }
    }
}
