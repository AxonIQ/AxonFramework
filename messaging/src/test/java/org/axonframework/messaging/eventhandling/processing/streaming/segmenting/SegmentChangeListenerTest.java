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

package org.axonframework.messaging.eventhandling.processing.streaming.segmenting;

import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SegmentChangeListener} factory methods and {@link SegmentChangeListener#andThen(SegmentChangeListener)}
 * composition behaviour around reset-position handling.
 */
class SegmentChangeListenerTest {

    private static final Segment SEGMENT = Segment.ROOT_SEGMENT;

    @Nested
    class Factories {

        @Test
        void onClaimReturnsNoReset() {
            // given
            SegmentChangeListener listener = SegmentChangeListener.onClaim(
                    segment -> CompletableFuture.completedFuture(null)
            );

            // when
            TrackingToken reset = listener.onSegmentClaimed(SEGMENT).join();

            // then
            assertThat(reset).isNull();
        }

        @Test
        void runOnClaimReturnsNoResetAndExecutesCallback() {
            // given
            List<Integer> observed = new CopyOnWriteArrayList<>();
            SegmentChangeListener listener = SegmentChangeListener.runOnClaim(
                    segment -> observed.add(segment.getSegmentId())
            );

            // when
            TrackingToken reset = listener.onSegmentClaimed(SEGMENT).join();

            // then
            assertThat(reset).isNull();
            assertThat(observed).containsExactly(SEGMENT.getSegmentId());
        }

        @Test
        void noOpReturnsNoReset() {
            // given
            SegmentChangeListener listener = SegmentChangeListener.noOp();

            // when / then
            assertThat(listener.onSegmentClaimed(SEGMENT).join()).isNull();
            assertThat(listener.onSegmentReleased(SEGMENT).join()).isNull();
        }

        @Test
        void onClaimWithResetPlumbsSuppliedResetPosition() {
            // given
            TrackingToken resetPosition = new GlobalSequenceTrackingToken(7);
            SegmentChangeListener listener = SegmentChangeListener.onClaimWithReset(
                    segment -> CompletableFuture.completedFuture(resetPosition)
            );

            // when
            TrackingToken reported = listener.onSegmentClaimed(SEGMENT).join();

            // then
            assertThat(reported).isEqualTo(resetPosition);
        }

        @Test
        void onReleaseDoesNotAffectReset() {
            // given
            SegmentChangeListener listener = SegmentChangeListener.onRelease(
                    segment -> CompletableFuture.completedFuture(null)
            );

            // when / then - claim side returns null, release side completes
            assertThat(listener.onSegmentClaimed(SEGMENT).join()).isNull();
            assertThat(listener.onSegmentReleased(SEGMENT).join()).isNull();
        }
    }

    @Nested
    class AndThen {

        @Test
        void mergesTwoResetPositionsViaLowerBound() {
            // given - first listener returns position 5, second returns position 2
            TrackingToken first = new GlobalSequenceTrackingToken(5);
            TrackingToken second = new GlobalSequenceTrackingToken(2);
            SegmentChangeListener listener = listenerReturning(first)
                    .andThen(listenerReturning(second));

            // when
            TrackingToken merged = listener.onSegmentClaimed(SEGMENT).join();

            // then - lower of the two positions wins
            assertThat(merged).isEqualTo(second);
        }

        @Test
        void nullFirstReturnsSecond() {
            // given
            TrackingToken second = new GlobalSequenceTrackingToken(2);
            SegmentChangeListener listener = SegmentChangeListener.noOp()
                                                                  .andThen(listenerReturning(second));

            // when / then
            assertThat(listener.onSegmentClaimed(SEGMENT).join()).isEqualTo(second);
        }

        @Test
        void nullSecondReturnsFirst() {
            // given
            TrackingToken first = new GlobalSequenceTrackingToken(5);
            SegmentChangeListener listener = listenerReturning(first)
                    .andThen(SegmentChangeListener.noOp());

            // when / then
            assertThat(listener.onSegmentClaimed(SEGMENT).join()).isEqualTo(first);
        }

        @Test
        void invokesListenersSequentially() {
            // given
            List<String> calls = new CopyOnWriteArrayList<>();
            SegmentChangeListener listener =
                    SegmentChangeListener.runOnClaim(s -> calls.add("first"))
                                         .andThen(SegmentChangeListener.runOnClaim(s -> calls.add("second")));

            // when
            listener.onSegmentClaimed(SEGMENT).join();

            // then
            assertThat(calls).containsExactly("first", "second");
        }

        @Test
        void mergesReleaseFutures() {
            // given
            List<String> calls = new CopyOnWriteArrayList<>();
            SegmentChangeListener listener =
                    SegmentChangeListener.runOnRelease(s -> calls.add("first"))
                                         .andThen(SegmentChangeListener.runOnRelease(s -> calls.add("second")));

            // when
            listener.onSegmentReleased(SEGMENT).join();

            // then
            assertThat(calls).containsExactly("first", "second");
        }
    }

    private static SegmentChangeListener listenerReturning(TrackingToken resetPosition) {
        return SegmentChangeListener.onClaimWithReset(
                segment -> CompletableFuture.completedFuture(resetPosition)
        );
    }
}
