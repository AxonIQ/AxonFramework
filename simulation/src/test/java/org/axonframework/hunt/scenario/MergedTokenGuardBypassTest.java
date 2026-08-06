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
import org.axonframework.messaging.eventhandling.processing.streaming.token.MergedTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the anti-rewind progress guard, while fully active, accepts a range token that rewinds the position the
 * segment resumes from.
 * <p>
 * The guard promises that a candidate which does not advance is "ignored with a warning rather than persisted, so a
 * misbehaving component can never rewind progress on any source". It judges the candidate on its unwrapped
 * <em>upper</em> bound. A {@link MergedTrackingToken} is a range: its upper half can advance while its lower half --
 * the position streaming resumes from on the next claim -- rewinds to the start of the stream. The guard sees only
 * the upper half, accepts, and the rewind becomes durable.
 * <p>
 * Unlike the first-store hole pinned by {@link DurableProgressMonotonicityTest}, this bypass works on every store:
 * the strategy here first advances the stored token, so the guard's comparison field is set and the guard runs -- and
 * still lets the rewind through.
 * <p>
 * <b>This test asserts the rewind, not the guarantee.</b> It is an expected-gap test: it passes while the gap is open
 * and turns red when the guard starts judging the candidate's resume position (its lower bound) as well as its upper
 * bound. A failure here is the good news.
 */
class MergedTokenGuardBypassTest {

    private static final String PROCESSOR = "merged-guard-probe";
    private static final long DURABLE_POSITION = 500L;
    private static final long ADVANCED_POSITION = 600L;
    private static final long UPPER_HALF_POSITION = 700L;

    @Test
    void aMergedCandidateRewindsTheResumePositionThroughTheActiveGuard() {
        // given a token store holding one segment's progress well into the stream
        TokenStore tokenStore = new InMemoryTokenStore();
        tokenStore.initializeTokenSegments(PROCESSOR, 1, new GlobalSequenceTrackingToken(DURABLE_POSITION), null)
                  .orTimeout(30, TimeUnit.SECONDS)
                  .join();

        // and a strategy that first advances the stored token -- so the guard's comparison field is set and the
        // guard is fully active -- and then offers a merged candidate whose upper half advances further while its
        // lower half names the start of the stream
        AdvanceThenMergedRewindStrategy.PERSISTS.set(0);
        try (ProgressProbeSupport.Harness harness =
                     ProgressProbeSupport.Harness.start(PROCESSOR,
                                                        tokenStore,
                                                        AdvanceThenMergedRewindStrategy::new,
                                                        150L)) {
            // when both persists have reached the framework
            Awaitility.await("the advancing store and the merged candidate")
                      .atMost(Duration.ofSeconds(30))
                      .pollInterval(Duration.ofMillis(25))
                      .untilAsserted(() -> assertThat(AdvanceThenMergedRewindStrategy.PERSISTS.get())
                              .isGreaterThanOrEqualTo(2));

            // then the store holds the merged candidate: the guard judged its upper bound and accepted the rewind
            Awaitility.await("the merged candidate to be persisted")
                      .atMost(Duration.ofSeconds(30))
                      .pollInterval(Duration.ofMillis(25))
                      .untilAsserted(() -> {
                          TrackingToken stored = tokenStore.fetchToken(PROCESSOR, 0, null)
                                                           .orTimeout(30, TimeUnit.SECONDS)
                                                           .join();
                          assertThat(stored).isInstanceOf(MergedTrackingToken.class);
                      });
            TrackingToken stored = tokenStore.fetchToken(PROCESSOR, 0, null)
                                             .orTimeout(30, TimeUnit.SECONDS)
                                             .join();
            long resumePosition = stored.position().orElse(-1L);
            System.out.println("MERGED GUARD BYPASS before=" + ADVANCED_POSITION
                                       + " storedResumePosition=" + resumePosition
                                       + " stored=" + stored);
            // The durable resume position rewound from 600 to the start of the stream, through the active guard.
            assertThat(resumePosition)
                    .as("expected gap: the guard accepted a range whose resume position is the start of the stream")
                    .isEqualTo(0L);
            // The processor is entirely healthy; the only trace of the rewind is the progress itself.
            assertThat(harness.processor().isRunning()).isTrue();
        }
    }

    /**
     * Advances the stored token once -- arming the guard -- and then offers the merged rewinding candidate, exactly
     * once, on every later cycle until it lands.
     */
    private static final class AdvanceThenMergedRewindStrategy implements SegmentProgressStrategy {

        private static final AtomicInteger PERSISTS = new AtomicInteger();

        private final SegmentProgressContext context;

        private AdvanceThenMergedRewindStrategy(SegmentProgressContext context) {
            this.context = context;
        }

        @Override
        public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
            TrackingToken candidate = PERSISTS.getAndIncrement() == 0
                    ? new GlobalSequenceTrackingToken(ADVANCED_POSITION)
                    : MergedTrackingToken.merged(TrackingToken.FIRST,
                                                 new GlobalSequenceTrackingToken(UPPER_HALF_POSITION));
            return context.persistProgress(candidate, processingContext);
        }

        @Override
        public boolean hasPendingWork() {
            return false;
        }
    }
}
