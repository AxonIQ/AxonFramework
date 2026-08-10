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

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class for {@link SegmentMatcher}.
 *
 * @author Mateusz Nowak
 */
class SegmentMatcherTest {

    @Test
    void matchesReturnsTrueWhenSegmentMatchesSequenceIdentifier() {
        // given the root segment, which matches every sequence identifier
        Segment rootSegment = Segment.ROOT_SEGMENT;

        // when
        boolean result = SegmentMatcher.matches(rootSegment, "sample-identifier");

        // then
        assertThat(result).isTrue();
    }

    @Test
    void matchesReturnsFalseWhenSegmentDoesNotMatchSequenceIdentifier() {
        // given a segment that matches identifiers with an odd hash
        Segment oddSegment = new Segment(1, 1);
        String evenIdentifier = "even"; // "even" has a hash code of 3021508, which is even

        // when
        boolean result = SegmentMatcher.matches(oddSegment, evenIdentifier);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void matchesRejectsNullSegmentAndNullSequenceIdentifier() {
        // given the resolved sequence identifier contract: callers apply any fallback before matching
        // when / then
        assertThatThrownBy(() -> SegmentMatcher.matches(null, "sample-identifier"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> SegmentMatcher.matches(Segment.ROOT_SEGMENT, null))
                .isInstanceOf(NullPointerException.class);
    }
}
