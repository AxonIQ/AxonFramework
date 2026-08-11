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

package org.axonframework.eventsourcing.eventstore;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link AggregateBasedConsistencyMarker}.
 *
 * @author Stefan Dragisic
 */
class AggregateBasedConsistencyMarkerTest {

    private static final String AGGREGATE_ID = "aggregate-one";
    private static final String OTHER_AGGREGATE_ID = "aggregate-two";

    /**
     * Returns the sequence number the next event of the given {@code aggregateIdentifier} would be appended with,
     * according to the given {@code marker}. This is the observable effect of a marker's position for an aggregate, as
     * an aggregate-based storage engine derives the sequence numbers of appended events from it.
     */
    private static long nextSequenceOf(ConsistencyMarker marker, String aggregateIdentifier) {
        return ((AggregateBasedConsistencyMarker) marker).createSequencer()
                                                         .incrementAndGetSequenceOf(aggregateIdentifier);
    }

    @Nested
    class LowerBound {

        @Test
        void keepsTheLowestPositionOfAnAggregateBothMarkersKnow() {
            // given
            ConsistencyMarker earlier = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 2);
            ConsistencyMarker later = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 7);

            // when
            ConsistencyMarker result = later.lowerBound(earlier);

            // then
            assertThat(result).isEqualTo(earlier);
            assertThat(result.position()).isEqualTo(new AggregateSequenceNumberPosition(3));
        }

        @Test
        void isSymmetric() {
            // given
            ConsistencyMarker earlier = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 2);
            ConsistencyMarker later = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 7);

            // when / then
            assertThat(earlier.lowerBound(later)).isEqualTo(later.lowerBound(earlier));
        }

        @Test
        void retainsThePositionOfAnAggregateOnlyOneMarkerKnows() {
            // given two sourcings in one processing context, each of a different aggregate
            ConsistencyMarker first = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 2);
            ConsistencyMarker second = new AggregateBasedConsistencyMarker(OTHER_AGGREGATE_ID, 7);

            // when
            ConsistencyMarker result = first.lowerBound(second);

            // then neither aggregate is reset to the start of its event stream
            assertThat(nextSequenceOf(result, AGGREGATE_ID)).isEqualTo(3);
            assertThat(nextSequenceOf(result, OTHER_AGGREGATE_ID)).isEqualTo(8);
        }

        @Test
        void combinesPerAggregateWhenMarkersPartiallyOverlap() {
            // given a marker of two aggregates, and a later marker of only the second aggregate
            ConsistencyMarker combined = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 2)
                    .lowerBound(new AggregateBasedConsistencyMarker(OTHER_AGGREGATE_ID, 7));
            ConsistencyMarker laterOther = new AggregateBasedConsistencyMarker(OTHER_AGGREGATE_ID, 9);

            // when
            ConsistencyMarker result = combined.lowerBound(laterOther);

            // then only the overlapping aggregate is lowered
            assertThat(nextSequenceOf(result, AGGREGATE_ID)).isEqualTo(3);
            assertThat(nextSequenceOf(result, OTHER_AGGREGATE_ID)).isEqualTo(8);
        }

        @Test
        void resolvesOriginAndInfinityWithoutConsultingTheAggregatePositions() {
            // given
            ConsistencyMarker testSubject = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 2);

            // when / then
            assertThat(testSubject.lowerBound(ConsistencyMarker.ORIGIN)).isEqualTo(ConsistencyMarker.ORIGIN);
            assertThat(testSubject.lowerBound(ConsistencyMarker.INFINITY)).isEqualTo(testSubject);
        }
    }

    @Nested
    class UpperBound {

        @Test
        void keepsTheHighestPositionOfAnAggregateBothMarkersKnow() {
            // given
            ConsistencyMarker earlier = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 2);
            ConsistencyMarker later = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 7);

            // when
            ConsistencyMarker result = earlier.upperBound(later);

            // then
            assertThat(result).isEqualTo(later);
            assertThat(result.position()).isEqualTo(new AggregateSequenceNumberPosition(8));
        }

        @Test
        void retainsThePositionOfAnAggregateOnlyOneMarkerKnows() {
            // given
            ConsistencyMarker first = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 2);
            ConsistencyMarker second = new AggregateBasedConsistencyMarker(OTHER_AGGREGATE_ID, 7);

            // when
            ConsistencyMarker result = first.upperBound(second);

            // then
            assertThat(nextSequenceOf(result, AGGREGATE_ID)).isEqualTo(3);
            assertThat(nextSequenceOf(result, OTHER_AGGREGATE_ID)).isEqualTo(8);
        }

        @Test
        void isSymmetric() {
            // given
            ConsistencyMarker earlier = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 2);
            ConsistencyMarker later = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 7);

            // when / then
            assertThat(earlier.upperBound(later)).isEqualTo(later.upperBound(earlier));
        }

        @Test
        void combinesPerAggregateWhenMarkersPartiallyOverlap() {
            // given a marker of two aggregates, and a later marker of only the second aggregate
            ConsistencyMarker combined = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 2)
                    .upperBound(new AggregateBasedConsistencyMarker(OTHER_AGGREGATE_ID, 7));
            ConsistencyMarker laterOther = new AggregateBasedConsistencyMarker(OTHER_AGGREGATE_ID, 9);

            // when
            ConsistencyMarker result = combined.upperBound(laterOther);

            // then only the overlapping aggregate is raised
            assertThat(nextSequenceOf(result, AGGREGATE_ID)).isEqualTo(3);
            assertThat(nextSequenceOf(result, OTHER_AGGREGATE_ID)).isEqualTo(10);
        }

        @Test
        void resolvesOriginAndInfinityWithoutConsultingTheAggregatePositions() {
            // given
            ConsistencyMarker testSubject = new AggregateBasedConsistencyMarker(AGGREGATE_ID, 2);

            // when / then
            assertThat(testSubject.upperBound(ConsistencyMarker.ORIGIN)).isEqualTo(testSubject);
            assertThat(testSubject.upperBound(ConsistencyMarker.INFINITY)).isEqualTo(ConsistencyMarker.INFINITY);
        }
    }
}
