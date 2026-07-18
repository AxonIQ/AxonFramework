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
    }
}
