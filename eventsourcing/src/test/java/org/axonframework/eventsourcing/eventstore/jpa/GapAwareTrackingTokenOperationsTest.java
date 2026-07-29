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

package org.axonframework.eventsourcing.eventstore.jpa;

import org.axonframework.messaging.eventhandling.processing.streaming.token.GapAwareTrackingToken;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the token advance rules of the {@link GapAwareTrackingTokenOperations}.
 *
 * @author Stefan Dragisic
 */
class GapAwareTrackingTokenOperationsTest {

    private static final int GAP_TIMEOUT = 60_000;
    private static final int MAX_GAP_OFFSET = 10_000;
    private static final long LOWEST_GLOBAL_SEQUENCE = 1L;

    private final GapAwareTrackingTokenOperations testSubject = operationsWith(MAX_GAP_OFFSET);

    private static GapAwareTrackingTokenOperations operationsWith(int maxGapOffset) {
        return new GapAwareTrackingTokenOperations(
                GAP_TIMEOUT,
                maxGapOffset,
                LOWEST_GLOBAL_SEQUENCE,
                LoggerFactory.getLogger(GapAwareTrackingTokenOperationsTest.class)
        );
    }

    @Nested
    class Advance {

        @Test
        void recordsEveryHoleBelowTheIndexThatWasRead() {
            // given a reader that has seen global index 5
            GapAwareTrackingToken token = GapAwareTrackingToken.newInstance(5L, List.of());

            // when the next row it reads is index 8, leaving 6 and 7 taken by transactions it cannot see
            GapAwareTrackingToken result = testSubject.advance(token, 8L);

            // then both holes are recorded, so the reader returns for them once those transactions commit
            assertThat(result.getIndex()).isEqualTo(8L);
            assertThat(result.getGaps()).containsExactly(6L, 7L);
        }

        @Test
        void recordsAHoleThatSpansAWholeBatchOfIndices() {
            // given a reader at the very start of the store
            GapAwareTrackingToken token = GapAwareTrackingToken.newInstance(0L, List.of());

            // when a single row far ahead of it becomes visible first
            GapAwareTrackingToken result = testSubject.advance(token, 100L);

            // then every index in between is recorded rather than skipped
            assertThat(result.getGaps()).hasSize(99);
            assertThat(result.getGaps().first()).isEqualTo(1L);
            assertThat(result.getGaps().last()).isEqualTo(99L);
        }

        @Test
        void keepsRemainingHolesWhenOneOfThemIsFilled() {
            // given a token whose gaps 6 and 7 are still outstanding below index 8
            GapAwareTrackingToken token = GapAwareTrackingToken.newInstance(8L, List.of(6L, 7L));

            // when the transaction holding index 6 commits and the row is read
            GapAwareTrackingToken result = testSubject.advance(token, 6L);

            // then the token stays at 8 and index 7 is still outstanding
            assertThat(result.getIndex()).isEqualTo(8L);
            assertThat(result.getGaps()).containsExactly(7L);
        }

        @Test
        void boundsRecordedHolesByTheConfiguredMaxGapOffset() {
            // given a store configured to look no further than three indices behind the token
            GapAwareTrackingTokenOperations narrow = operationsWith(3);
            GapAwareTrackingToken token = GapAwareTrackingToken.newInstance(10L, List.of());

            // when a row twenty indices ahead becomes visible
            GapAwareTrackingToken result = narrow.advance(token, 30L);

            // then only the holes within that distance of the new index are carried
            assertThat(result.getIndex()).isEqualTo(30L);
            assertThat(result.getGaps()).containsExactly(27L, 28L, 29L);
        }

        @Test
        void boundsTheFirstTokensHolesByTheConfiguredMaxGapOffset() {
            // given a reader with no token yet, against a store whose lowest surviving index is far above the
            // configured lowest global sequence
            GapAwareTrackingTokenOperations narrow = operationsWith(3);

            // when its first visible row is index 1000
            GapAwareTrackingToken result = narrow.advance(null, 1000L);

            // then the token it starts from does not enumerate every index the table never held
            assertThat(result.getIndex()).isEqualTo(1000L);
            assertThat(result.getGaps()).containsExactly(997L, 998L, 999L);
        }
    }
}
