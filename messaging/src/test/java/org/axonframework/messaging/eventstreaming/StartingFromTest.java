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

package org.axonframework.messaging.eventstreaming;

import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link StartingFrom}.
 *
 * @author Steven van Beelen
 */
class StartingFromTest {

    private static final GlobalSequenceTrackingToken TEST_POSITION = new GlobalSequenceTrackingToken(1337);
    private static final EventCriteria TEST_CRITERIA = EventCriteria.havingTags("key", "value");

    private StreamingCondition testSubject;

    @BeforeEach
    void setUp() {
        testSubject = StreamingCondition.startingFrom(TEST_POSITION);
    }

    @Test
    void containsExpectedData() {
        assertEquals(TEST_POSITION, testSubject.position());
        assertEquals(EventCriteria.havingAnyTag(), testSubject.criteria());
    }

    @Test
    void orReplacesTheMatchAnyCriteriaWithTheGivenCriteria() {
        // given a StartingFrom whose default criteria matches any tag
        assertEquals(EventCriteria.havingAnyTag(), testSubject.criteria());

        // when it is combined with concrete criteria
        StreamingCondition result = testSubject.or(TEST_CRITERIA);

        // then the given criteria takes over
        assertEquals(TEST_CRITERIA, result.criteria());
    }

    @Test
    void nullPositionIsNormalizedToFirst() {
        // when a StartingFrom is constructed without a position
        StreamingCondition nullPositionTestSubject = StreamingCondition.startingFrom(null);

        // then the position defaults to TrackingToken.FIRST rather than staying null
        assertEquals(TrackingToken.FIRST, nullPositionTestSubject.position());
    }

    @Test
    void orCombinesCriteriaWhenPositionWasNull() {
        // given a StartingFrom constructed without a position
        StreamingCondition nullPositionTestSubject = StreamingCondition.startingFrom(null);

        // when combining criteria
        StreamingCondition result = nullPositionTestSubject.or(TEST_CRITERIA);

        // then the normalized FIRST position and the given criteria are used
        assertEquals(TrackingToken.FIRST, result.position());
        assertEquals(TEST_CRITERIA, result.criteria());
    }

    @Test
    void withCriteriaSetsTheGivenCriteriaPreservingThePosition() {
        // given a StartingFrom whose default criteria matches any tag
        assertEquals(EventCriteria.havingAnyTag(), testSubject.criteria());

        // when its criteria is replaced
        StreamingCondition result = testSubject.withCriteria(TEST_CRITERIA);

        // then the position is preserved and the given criteria is used
        assertEquals(TEST_POSITION, result.position());
        assertEquals(TEST_CRITERIA, result.criteria());
    }

    @Test
    void withCriteriaSetsTheGivenCriteriaWhenPositionWasNull() {
        // given a StartingFrom constructed without a position
        StreamingCondition nullPositionTestSubject = StreamingCondition.startingFrom(null);

        // when its criteria is replaced
        StreamingCondition result = nullPositionTestSubject.withCriteria(TEST_CRITERIA);

        // then the normalized FIRST position and the given criteria are used
        assertEquals(TrackingToken.FIRST, result.position());
        assertEquals(TEST_CRITERIA, result.criteria());
    }
}