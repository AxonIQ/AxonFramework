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
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a claim rejected by a progress participant is recovered from: the work package must be aborted, the
 * token-store claim released rather than leaked, and the segment claimed again on a later coordination run.
 * <p>
 * A {@link SegmentProgressStrategy} is told when its segment is claimed, before any events are handled, and may
 * refuse by throwing -- a participant that cannot accept a claim yet. The coordinator must treat that as a failed
 * claim: abort the just-built package and release the claim, so the segment is not owned by a package that never
 * processes and not left claimed in the store for the length of a claim timeout.
 * <p>
 * The store here records the order of claim rejections and claim releases, so the probe can assert the release
 * happened as part of handling the rejection -- before the segment was claimed again -- rather than inferring it
 * from recovery alone.
 */
class SegmentClaimFailureRecoveryTest {

    private static final String PROCESSOR = "claim-failure-probe";

    @Test
    void aThrowingClaimCallbackAbortsThePackageReleasesTheClaimAndTheSegmentIsClaimedAgain() {
        // given a store recording claim releases, and a strategy that rejects the first claim of its segment
        RecordingReleaseTokenStore tokenStore = new RecordingReleaseTokenStore(new InMemoryTokenStore());
        tokenStore.initializeTokenSegments(PROCESSOR, 1, new GlobalSequenceTrackingToken(0L), null)
                  .orTimeout(30, TimeUnit.SECONDS)
                  .join();
        RejectFirstClaimStrategy.CLAIMS.set(0);
        RejectFirstClaimStrategy.EVENTS = tokenStore.events();
        try (ProgressProbeSupport.Harness harness =
                     ProgressProbeSupport.Harness.start(PROCESSOR, tokenStore, RejectFirstClaimStrategy::new, 100L)) {

            // when the first claim is rejected and coordination continues
            Awaitility.await("the rejected claim and the successful re-claim")
                      .atMost(Duration.ofSeconds(30))
                      .pollInterval(Duration.ofMillis(25))
                      .untilAsserted(() -> assertThat(RejectFirstClaimStrategy.CLAIMS.get())
                              .isGreaterThanOrEqualTo(2));

            // then the segment is claimed again and healthy
            Awaitility.await("the segment to be processing again")
                      .atMost(Duration.ofSeconds(30))
                      .pollInterval(Duration.ofMillis(25))
                      .untilAsserted(() -> {
                          assertThat(harness.processor().processingStatus()).hasSize(1);
                          assertThat(harness.processor().processingStatus().values())
                                  .noneMatch(status -> status.isErrorState());
                      });

            // and the claim was released as part of handling the rejection: reject, release, then the re-claim
            List<String> events = List.copyOf(tokenStore.events());
            System.out.println("CLAIM FAILURE RECOVERY events=" + events);
            int reject = events.indexOf("claim-rejected");
            int release = events.indexOf("claim-released");
            int reclaim = events.lastIndexOf("claim-accepted");
            assertThat(reject).as("the first claim was rejected").isNotNegative();
            assertThat(release).as("the rejected claim was released, not leaked").isGreaterThan(reject);
            assertThat(reclaim).as("the segment was claimed again after the release").isGreaterThan(release);
        } finally {
            RejectFirstClaimStrategy.EVENTS = null;
        }
    }

    /**
     * Rejects the first claim of its segment and accepts every later one -- a participant that was not ready yet.
     */
    private static final class RejectFirstClaimStrategy implements SegmentProgressStrategy {

        private static final AtomicInteger CLAIMS = new AtomicInteger();
        private static volatile @Nullable CopyOnWriteArrayList<String> EVENTS;

        @SuppressWarnings("unused")
        private final SegmentProgressContext context;

        private RejectFirstClaimStrategy(SegmentProgressContext context) {
            this.context = context;
        }

        @Override
        public void onSegmentClaimed() {
            CopyOnWriteArrayList<String> events = EVENTS;
            if (CLAIMS.getAndIncrement() == 0) {
                if (events != null) {
                    events.add("claim-rejected");
                }
                throw new IllegalStateException("A participant rejects the first claim of this segment.");
            }
            if (events != null) {
                events.add("claim-accepted");
            }
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }
    }

    /**
     * Records every {@code releaseClaim} into the shared event list, so release-vs-reclaim ordering is observable on
     * a store that otherwise arbitrates nothing.
     */
    private static final class RecordingReleaseTokenStore extends ForwardingTokenStore {

        private final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();

        private RecordingReleaseTokenStore(TokenStore delegate) {
            super(delegate);
        }

        CopyOnWriteArrayList<String> events() {
            return events;
        }

        @Override
        public CompletableFuture<Void> releaseClaim(String processorName,
                                                    int segmentId,
                                                    @Nullable ProcessingContext context) {
            events.add("claim-released");
            return super.releaseClaim(processorName, segmentId, context);
        }
    }
}
