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

package org.axonframework.messaging.eventhandling.processing.streaming.token;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link TrackingTokenUtils} collection-combining helpers.
 *
 * @author Allard Buijze
 */
class TrackingTokenUtilsTest {

    private static GlobalSequenceTrackingToken token(long index) {
        return new GlobalSequenceTrackingToken(index);
    }

    @Nested
    class LowerBound {

        @Test
        void returnsTheLowestOfSeveralTokens() {
            // given
            List<TrackingToken> tokens = Arrays.asList(token(5), token(2), token(8));

            // when
            TrackingToken result = TrackingTokenUtils.lowerBound(tokens);

            // then
            assertThat(result).isEqualTo(token(2));
        }

        @Test
        void ignoresNullElements() {
            // given
            List<TrackingToken> tokens = Arrays.asList(token(5), null, token(3), null);

            // when
            TrackingToken result = TrackingTokenUtils.lowerBound(tokens);

            // then
            assertThat(result).isEqualTo(token(3));
        }

        @Test
        void returnsFirstWhenThereAreNoTokens() {
            // when
            TrackingToken result = TrackingTokenUtils.lowerBound(emptyList());

            // then
            assertThat(result).isEqualTo(TrackingToken.FIRST);
        }

        @Test
        void returnsFirstWhenAllTokensAreNull() {
            // when
            TrackingToken result = TrackingTokenUtils.lowerBound(Arrays.asList(null, null));

            // then
            assertThat(result).isEqualTo(TrackingToken.FIRST);
        }
    }

    @Nested
    class UpperBound {

        @Test
        void returnsTheHighestOfSeveralTokens() {
            // given
            List<TrackingToken> tokens = Arrays.asList(token(5), token(2), token(8));

            // when
            TrackingToken result = TrackingTokenUtils.upperBound(tokens);

            // then
            assertThat(result).isEqualTo(token(8));
        }

        @Test
        void ignoresNullElements() {
            // given
            List<TrackingToken> tokens = Arrays.asList(token(5), null, token(7), null);

            // when
            TrackingToken result = TrackingTokenUtils.upperBound(tokens);

            // then
            assertThat(result).isEqualTo(token(7));
        }

        @Test
        void returnsNullWhenThereAreNoTokens() {
            // when
            TrackingToken result = TrackingTokenUtils.upperBound(emptyList());

            // then
            assertThat(result).isNull();
        }

        @Test
        void returnsNullWhenAllTokensAreNull() {
            // when
            TrackingToken result = TrackingTokenUtils.upperBound(Arrays.asList(null, null));

            // then
            assertThat(result).isNull();
        }
    }

    @Nested
    class CoversWhenUnwrapped {

        @Test
        void reportsTrueWhenCandidateIsAheadOfReference() {
            // when / then
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(token(5), token(3))).isTrue();
        }

        @Test
        void reportsTrueWhenCandidateEqualsReference() {
            // when / then
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(token(5), token(5))).isTrue();
        }

        @Test
        void reportsFalseWhenCandidateIsBehindReference() {
            // when / then
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(token(3), token(5))).isFalse();
        }

        @Test
        void reportsAdvanceAcrossAConcludingReplayBoundary() {
            // given -- reference still wraps the reset position while the candidate is the plain token it advances to
            TrackingToken replaying = ReplayToken.createReplayToken(token(5), token(0));

            // when / then -- comparing unwrapped upper-bound positions reports the transition as an advance
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(token(5), replaying)).isTrue();
        }

        @Test
        void reportsAdvanceWhenAConcludingReplayIsTheCandidate() {
            // given -- the candidate concludes a replay at a position beyond the plain reference
            TrackingToken concluding = ReplayToken.createReplayToken(token(5), token(4));

            // when / then
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(concluding, token(3))).isTrue();
        }

        @Test
        void reportsFalseWhenTheLowerHalfOfAMergedCandidateRegresses() {
            // given -- a merged range whose furthest half is ahead, but which resumes from the start of the stream
            TrackingToken rewinding = MergedTrackingToken.merged(TrackingToken.FIRST, token(7));

            // when / then -- the resume position falls behind the reference, so this is not an advance
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(rewinding, token(6))).isFalse();
        }

        @Test
        void reportsTrueWhenBothHalvesOfAMergedCandidateAreAhead() {
            // given -- a merged range entirely beyond the reference
            TrackingToken advancing = MergedTrackingToken.merged(token(7), token(9));

            // when / then
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(advancing, token(6))).isTrue();
        }

        @Test
        void reportsFalseWhenTheUpperHalfOfAMergedCandidateIsBehind() {
            // given -- the furthest half of the merged range has not reached the reference
            TrackingToken behind = MergedTrackingToken.merged(token(2), token(4));

            // when / then
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(behind, token(6))).isFalse();
        }

        @Test
        void reportsFalseWhenTheCandidateHasNoPositionYet() {
            // given -- a freshly created reset token, which has no current position at all
            TrackingToken freshReset = ReplayToken.createReplayToken(token(5));

            // when / then -- an unset position is not an advance, and must not throw
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(freshReset, token(3))).isFalse();
        }

        @Test
        void reportsAdvanceWhenTheReferenceHasNoPositionYet() {
            // given -- the reference is a freshly created reset token, which has no current position at all
            TrackingToken freshReset = ReplayToken.createReplayToken(token(5));

            // when / then -- there is no progress to regress from, so the candidate advances beyond it
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(token(3), freshReset)).isTrue();
        }

        @Test
        void reportsAdvanceWhenTheReferenceHasNoPositionYetForARawTypeRejectingNull() {
            // given -- a reset that has not consumed anything yet unwraps to no raw position at all. The raw token type
            // used here rejects a null argument to covers(..), so the comparison must not reach it.
            TrackingToken freshReset =
                    ReplayToken.createReplayToken(GapAwareTrackingToken.newInstance(5L, emptyList()));

            // when / then -- there is no position to regress from, so the candidate covers it
            assertThat(TrackingTokenUtils.coversWhenUnwrapped(GapAwareTrackingToken.newInstance(1L, emptyList()),
                                                              freshReset)).isTrue();
        }
    }
}
