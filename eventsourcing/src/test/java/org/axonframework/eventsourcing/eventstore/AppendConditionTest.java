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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the static factories of {@link AppendCondition}.
 *
 * @author Mateusz Nowak
 */
class AppendConditionTest {

    private final EventCriteria criteria = EventCriteria.havingTags("username", "jane");

    @Nested
    class WithUnsourcedCriteria {

        @Test
        void checksTheGivenCriteriaFromOrigin() {
            // when
            AppendCondition result = AppendCondition.withUnsourcedCriteria(criteria);

            // then the criteria are checked against the whole history, like withCriteria
            assertThat(result.criteria()).isEqualTo(criteria);
            assertThat(result.consistencyMarker()).isEqualTo(ConsistencyMarker.ORIGIN);
        }

        @Test
        void staysRecognizableAsUnsourcedWhenItsCriteriaAreReplaced() {
            // given
            EventCriteria otherCriteria = EventCriteria.havingTags("username", "john");

            // when
            AppendCondition result = AppendCondition.withUnsourcedCriteria(criteria).replaceCriteria(otherCriteria);

            // then
            assertThat(result.criteria()).isEqualTo(otherCriteria);
            assertThatThrownBy(() -> AggregateBasedConsistencyMarker.from(result))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNullCriteria() {
            // when / then
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> AppendCondition.withUnsourcedCriteria(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
