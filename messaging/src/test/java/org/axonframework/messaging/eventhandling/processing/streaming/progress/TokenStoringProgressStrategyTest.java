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

package org.axonframework.messaging.eventhandling.processing.streaming.progress;

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the default {@link TokenStoringProgressStrategy} (and the
 * {@link SegmentProgressStrategyFactory#tokenStoring() factory} that produces it) against a recording
 * {@link SegmentProgressContext}: it persists the batch-end token, schedules no out-of-band work, and leaves the
 * release/abort behaviour at the no-op defaults.
 *
 * @author Allard Buijze
 */
class TokenStoringProgressStrategyTest {

    private RecordingProgressContext context;
    private SegmentProgressStrategy testSubject;

    @BeforeEach
    void setUp() {
        context = new RecordingProgressContext();
        testSubject = SegmentProgressStrategyFactory.tokenStoring().create(context);
    }

    @Nested
    class OnBatchCommit {

        @Test
        void persistsTheLastConsumedToken() {
            // given
            TrackingToken consumed = new GlobalSequenceTrackingToken(7L);
            context.lastConsumedToken = consumed;

            // when
            testSubject.onBatchCommit(null).orTimeout(5, TimeUnit.SECONDS).join();

            // then
            assertThat(context.persisted).containsExactly(consumed);
        }

        @Test
        void persistsNothingWhenNothingHasBeenConsumed() {
            // given -- nothing consumed yet
            context.lastConsumedToken = null;

            // when
            testSubject.onBatchCommit(null).orTimeout(5, TimeUnit.SECONDS).join();

            // then
            assertThat(context.persisted).isEmpty();
        }
    }

    @Nested
    class Defaults {

        @Test
        void neverSchedulesOutOfBandWork() {
            // given
            context.lastConsumedToken = new GlobalSequenceTrackingToken(3L);

            // when / then -- the default strategy acts only within batches, so it has no pending out-of-band work
            assertThat(testSubject.hasPendingWork()).isFalse();
        }

        @Test
        void onSegmentReleasedPersistsNothing() {
            // given
            context.lastConsumedToken = new GlobalSequenceTrackingToken(3L);

            // when
            testSubject.onSegmentReleased(null).orTimeout(5, TimeUnit.SECONDS).join();

            // then -- the per-batch store already covered progress; release is a no-op
            assertThat(context.persisted).isEmpty();
        }

        @Test
        void onAbortDoesNotThrow() {
            // when / then
            testSubject.onAbort();
            assertThat(context.persisted).isEmpty();
        }
    }

    /**
     * Recording {@link SegmentProgressContext}: captures the tokens the strategy asks to persist (mirroring the work
     * package's monotonic store without the token-store machinery).
     */
    private static class RecordingProgressContext implements SegmentProgressContext {

        private final List<TrackingToken> persisted = new ArrayList<>();
        private @Nullable TrackingToken lastConsumedToken;

        @Override
        public Segment segment() {
            return Segment.ROOT_SEGMENT;
        }

        @Override
        public @Nullable TrackingToken lastConsumedToken() {
            return lastConsumedToken;
        }

        @Override
        public void scheduleWorker() {
            // no-op for the default strategy, which never schedules out-of-band work
        }

        @Override
        public CompletableFuture<Void> persistProgress(@Nullable TrackingToken candidate, ProcessingContext context) {
            if (candidate != null) {
                persisted.add(candidate);
            }
            return FutureUtils.emptyCompletedFuture();
        }
    }
}
