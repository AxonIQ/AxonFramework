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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link DefaultSourcingCondition}.
 *
 * @author Steven van Beelen
 * @author John Hendrikx
 */
class DefaultSourcingConditionTest {

    private static final EventCriteria TEST_CRITERIA = EventCriteria.havingTags("key", "value");

    @Test
    void throwsExceptionWhenConstructingWithNullEventCriteria() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class,
                     () -> new DefaultSourcingCondition(new SourcingStrategy.Absolute(Position.START), null));
    }

    @Test
    void combineUsesTheSmallestStartValue() {
        GlobalIndexPosition position1 = new GlobalIndexPosition(10);
        GlobalIndexPosition position2 = new GlobalIndexPosition(20);
        SourcingCondition sc1 = new DefaultSourcingCondition(new SourcingStrategy.Absolute(position1), TEST_CRITERIA);
        SourcingCondition sc2 = new DefaultSourcingCondition(new SourcingStrategy.Absolute(position2), TEST_CRITERIA);

        SourcingCondition result1 = sc1.or(sc2);
        SourcingCondition result2 = sc2.or(sc1);

        assertNotEquals(position2, result1.start());
        assertEquals(position1, result1.start());
        assertNotEquals(position2, result2.start());
        assertEquals(position1, result2.start());
    }

    @Test
    void combineWithStandardPositionUsesTheSmallestStartValue() {
        GlobalIndexPosition position = new GlobalIndexPosition(10);
        SourcingCondition sc1 = new DefaultSourcingCondition(new SourcingStrategy.Absolute(Position.START), TEST_CRITERIA);
        SourcingCondition sc2 = new DefaultSourcingCondition(new SourcingStrategy.Absolute(position), TEST_CRITERIA);

        SourcingCondition result1 = sc1.or(sc2);
        SourcingCondition result2 = sc2.or(sc1);

        assertNotEquals(position, result1.start());
        assertEquals(Position.START, result1.start());
        assertNotEquals(position, result2.start());
        assertEquals(Position.START, result2.start());
    }
}