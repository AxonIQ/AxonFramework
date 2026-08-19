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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link NoAppendCondition}.
 *
 * @author Steven van Beelen
 */
class NoAppendConditionTest {

    private static final EventCriteria TEST_CRITERIA = EventCriteria.havingTags("key", "value");

    @Test
    void consistencyMarkerFixedToLongMax() {
        assertEquals(ConsistencyMarker.INFINITY, AppendCondition.none().consistencyMarker());
    }

    @Test
    void criteriaFixedToNoCriteria() {
        assertEquals(EventCriteria.havingAnyTag(), AppendCondition.none().criteria());
    }

    @Test
    void withMarkerReturnsDefaultAppendConditionWithHavingAnyTagCriteria() {
        // given
        ConsistencyMarker marker = new GlobalIndexConsistencyMarker(42);

        // when
        AppendCondition result = AppendCondition.none().withMarker(marker);

        // then

        assertThat(result.consistencyMarker()).isEqualTo(marker);
        assertThat(result.criteria()).isEqualTo(EventCriteria.havingAnyTag());
    }

    @Test
    void replaceCriteriaReturnsDefaultAppendConditionWithInfinityMarker() {
        // when
        AppendCondition result = AppendCondition.none().replaceCriteria(TEST_CRITERIA);

        // then

        assertThat(result.consistencyMarker()).isEqualTo(ConsistencyMarker.INFINITY);
        assertThat(result.criteria()).isEqualTo(TEST_CRITERIA);
    }

    @Test
    void witherMethodsChainingInAnyOrderProducesSameResult() {
        // given
        ConsistencyMarker marker = new GlobalIndexConsistencyMarker(42);

        // when
        AppendCondition criteriaFirst = AppendCondition.none().replaceCriteria(TEST_CRITERIA).withMarker(marker);
        AppendCondition markerFirst = AppendCondition.none().withMarker(marker).replaceCriteria(TEST_CRITERIA);

        // then
        assertThat(criteriaFirst).isEqualTo(markerFirst);
        assertThat(criteriaFirst.consistencyMarker()).isEqualTo(marker);
        assertThat(criteriaFirst.criteria()).isEqualTo(TEST_CRITERIA);
    }
}