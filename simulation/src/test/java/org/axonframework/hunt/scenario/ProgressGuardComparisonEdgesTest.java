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

import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.MergedTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingTokenUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The edges of the comparison the anti-rewind progress guard stands on:
 * {@link TrackingTokenUtils#coversWhenUnwrapped(TrackingToken, TrackingToken)}.
 * <p>
 * The guard's contract is that a candidate which advances is accepted, and one that regresses on any source -- or is
 * incomparable, such as a partially-regressed multi-axis position -- is refused. The cases here probe both directions
 * of the replay boundary, equal positions, gap-aware partial regressions, and the two edges where the comparison does
 * not do what the guard's contract says:
 * <ul>
 * <li><b>A range token hides a lower-half rewind.</b> The comparison collapses a {@link MergedTrackingToken} to its
 * upper bound, so a merged candidate whose lower half is behind the reference -- all the way at
 * {@link TrackingToken#FIRST} -- passes as an advance. The lower half is the position the segment resumes from, so
 * the accepted candidate rewinds durable progress while the guard is active.</li>
 * <li><b>A fresh reset token throws instead of being refused.</b> {@link ReplayToken#createReplayToken(TrackingToken)}
 * with no start position leaves the current position {@code null}; unwrapping it yields {@code null} and the
 * comparison throws {@link NullPointerException} rather than reporting a non-advance.</li>
 * </ul>
 * The tests for those two edges are expected-gap tests: they pass while the gap exists and turn red when the
 * comparison is fixed. A failure there is the good news.
 */
class ProgressGuardComparisonEdgesTest {

    private static TrackingToken global(long position) {
        return new GlobalSequenceTrackingToken(position);
    }

    @Nested
    class AcrossTheReplayBoundary {

        @Test
        void concludingReplayCountsAsAdvance() {
            // given a stored token still wrapped in a replay, and the plain live token the replay concludes to
            TrackingToken storedDuringReplay = ReplayToken.createReplayToken(global(10), global(8));
            TrackingToken liveAfterReplay = global(12);

            // when / then the transition out of the replay is judged an advance, not a regression
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(liveAfterReplay, storedDuringReplay)).isTrue();
        }

        @Test
        void advanceWithinAReplayCountsAsAdvance() {
            // given two positions of the same replay
            TrackingToken earlier = ReplayToken.createReplayToken(global(20), global(5));
            TrackingToken later = ReplayToken.createReplayToken(global(20), global(8));

            // when / then progress within the replay is an advance
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(later, earlier)).isTrue();
        }

        @Test
        void enteringAReplayBehindTheStoredPositionIsRefused() {
            // given a live stored position and a replay candidate positioned behind it
            TrackingToken live = global(10);
            TrackingToken replayBehind = ReplayToken.createReplayToken(global(20), global(5));

            // when / then a rewind through the progress seam is refused; a reset travels its own path
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(replayBehind, live)).isFalse();
        }
    }

    @Nested
    class OnEqualAndInitialPositions {

        @Test
        void anEqualPositionCovers() {
            // given / when / then
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(global(7), global(7))).isTrue();
        }

        @Test
        void lowerBoundOfNoParticipantsIsTheStartOfTheStream() {
            // given the reconcile helper fed only participants that have not reported a position
            TrackingToken safe = TrackingTokenUtils.lowerBound(java.util.Collections.singletonList(null));

            // when / then it names the start of the stream, and the guard judges it a regression of any progress
            assertThat(safe).isEqualTo(TrackingToken.FIRST);
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(safe, global(1))).isFalse();
        }
    }

    @Nested
    class OnGapAwarePartialRegressions {

        @Test
        void anIndexAdvanceThatReopensAProcessedPositionIsRefused() {
            // given a reference that processed everything up to 10, and a candidate whose index advanced to 15
            // while position 7 -- which the reference had processed -- became a gap again
            TrackingToken reference = GapAwareTrackingToken.newInstance(10, List.of());
            TrackingToken candidate = GapAwareTrackingToken.newInstance(15, List.of(7L));

            // when / then one axis advanced and one regressed: the incomparable candidate is refused
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(candidate, reference)).isFalse();
        }

        @Test
        void anIndexAdvanceWithOnlyNewGapsIsAccepted() {
            // given a candidate whose only gaps lie beyond everything the reference processed
            TrackingToken reference = GapAwareTrackingToken.newInstance(10, List.of());
            TrackingToken candidate = GapAwareTrackingToken.newInstance(15, List.of(12L));

            // when / then a genuine advance is accepted
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(candidate, reference)).isTrue();
        }
    }

    /**
     * The two edges where the comparison does not do what the guard's contract says. Both are expected-gap tests:
     * green while the gap exists, red the moment it closes.
     */
    @Nested
    class WhereTheComparisonBreaksItsContract {

        @Test
        void aMergedCandidateWhoseLowerHalfRewindsToTheStartPassesAsAnAdvance() {
            // given a stored plain position, and a merged candidate whose upper half advances beyond it while its
            // lower half -- the position the segment resumes from -- is the start of the stream
            TrackingToken reference = global(500);
            TrackingToken candidate = MergedTrackingToken.merged(TrackingToken.FIRST, global(700));

            // when the guard's comparison judges the candidate on its collapsed upper bound
            boolean accepted = TrackingTokenUtils.coversWhenUnwrapped(candidate, reference);

            // then the partially-regressed range passes as an advance: the rewind the guard exists to refuse
            assertThat(accepted)
                    .as("expected gap: the comparison collapses a range token to its upper bound, "
                                + "so a lower-half rewind to the start of the stream is accepted")
                    .isTrue();
            // and the accepted candidate really is a rewind: the position a resume would start from is 0
            assertThat(candidate.position()).hasValue(0L);
        }

        @Test
        void aFreshResetTokenThrowsInsteadOfBeingRefused() {
            // given a reset token exactly as the framework's own factory creates it for a reset with no start
            // position: the current position is not set yet
            TrackingToken freshReset = ReplayToken.createReplayToken(global(10));

            // when / then unwrapping the unstarted replay yields null and the comparison throws, where the guard's
            // contract promises a refusal with a warning
            assertThatThrownBy(() -> TrackingTokenUtils.coversWhenUnwrapped(freshReset, global(5)))
                    .as("expected gap: an unstarted replay candidate blows up the guard instead of being ignored")
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void anythingCoversAFreshResetReference() {
            // given the fresh reset token on the reference side instead
            TrackingToken freshReset = ReplayToken.createReplayToken(global(10));

            // when / then a candidate at the very start covers the unstarted reference; consistent with treating an
            // unstarted replay as no position at all, and documented here so a change is noticed
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(global(0), freshReset)).isTrue();
        }
    }
}
