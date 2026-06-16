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

import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.junit.jupiter.api.*;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link DefaultAppendCondition}.
 *
 * @author Steven van Beelen
 */
class DefaultAppendConditionTest {

    private static final ConsistencyMarker TEST_CONSISTENCY_MARKER = new GlobalIndexConsistencyMarker(10);
    private static final EventCriteria TEST_CRITERIA = EventCriteria.havingTags("key", "value");
    private static final EventCriteria OTHER_CRITERIA = EventCriteria.havingTags("other_key", "other");

    private AppendCondition testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new DefaultAppendCondition(TEST_CONSISTENCY_MARKER, TEST_CRITERIA);
    }

    @Test
    void throwsExceptionWhenConstructingWithNullEventCriteria() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class,
                     () -> new DefaultAppendCondition(ConsistencyMarker.ORIGIN, (EventCriteria) null));
    }

    @Test
    void containsExpectedData() {
        assertEquals(TEST_CONSISTENCY_MARKER, testSubject.consistencyMarker());
        assertEquals(Set.of(TEST_CRITERIA), testSubject.criteria().flatten());
    }

    @Test
    void withMarkerChangesMarkerButLeavesConditions() {
        ConsistencyMarker testConsistencyMarker = new GlobalIndexConsistencyMarker(5);

        AppendCondition result = testSubject.withMarker(testConsistencyMarker);

        assertEquals(testConsistencyMarker, result.consistencyMarker());
        assertEquals(testSubject.criteria(), result.criteria());
    }

    @Test
    void orCriteriaAreCombinedWithExistingCriteria() {
        ConsistencyMarker testConsistencyMarker = new GlobalIndexConsistencyMarker(5);

        AppendCondition result = testSubject.withMarker(testConsistencyMarker)
                                            .orCriteria(TEST_CRITERIA)
                                            .orCriteria(OTHER_CRITERIA);
        assertTrue(result.criteria().flatten().contains(TEST_CRITERIA));
        assertTrue(result.criteria().flatten().contains(OTHER_CRITERIA));
    }

    @Nested
    class ReplaceCriteria {

        @Test
        void preservesMarkerAndReplacesCriteria() {
            // when
            AppendCondition result = testSubject.replaceCriteria(OTHER_CRITERIA);

            // then
            assertEquals(TEST_CONSISTENCY_MARKER, result.consistencyMarker());
            assertEquals(Set.of(OTHER_CRITERIA), result.criteria().flatten());
        }

        @Test
        void returnsSameInstanceWhenCriteriaUnchanged() {
            // when
            AppendCondition result = testSubject.replaceCriteria(TEST_CRITERIA);

            // then
            assertSame(testSubject, result);
        }
    }
}