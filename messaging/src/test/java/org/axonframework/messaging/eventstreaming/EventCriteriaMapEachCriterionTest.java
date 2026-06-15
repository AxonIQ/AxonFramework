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

import org.axonframework.messaging.core.QualifiedName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Verifies {@link EventCriteria#mapEachCriterion(Function)}: each flattened {@link EventCriterion} is rewritten by the given
 * mapper and the results recombined, while a criteria with nothing to map (or a mapper that changes nothing) is returned
 * as the same instance so callers can rely on a zero-cost no-op.
 */
class EventCriteriaMapEachCriterionTest {

    private static final QualifiedName ONE_TYPE = new QualifiedName("OneType");
    private static final QualifiedName OTHER_TYPE = new QualifiedName("OtherType");
    private static final QualifiedName EXTRA_TYPE = new QualifiedName("ExtraType");
    private static final Tag TAG = new Tag("key1", "value1");

    /**
     * Returns a mapper that adds {@link #EXTRA_TYPE} to the types of every flattened criterion, preserving its tags.
     */
    private static Function<EventCriterion, EventCriteria> addingExtraType() {
        return criterion -> {
            Set<QualifiedName> widened = new HashSet<>(criterion.types());
            widened.add(EXTRA_TYPE);
            return EventCriteria.havingTags(criterion.tags()).andBeingOneOfTypes(widened);
        };
    }

    /**
     * A mapper that always returns {@code null}, used to exercise {@code mapEachCriterion}'s per-criterion null-rejection
     * guard. The unbounded type variable lets the {@code null} return be legal at the source, so the deliberate
     * contract violation does not need an inspection suppression to compile cleanly.
     */
    private static <T, R> Function<T, R> alwaysNull() {
        return criterion -> null;
    }

    @Nested
    class NoOp {

        @Test
        void matchAllCriteriaIsReturnedUnchanged() {
            // given a match-anything criteria that flattens to no criterion
            EventCriteria criteria = EventCriteria.havingAnyTag();

            // when mapping with a mapper that would otherwise widen every criterion
            EventCriteria result = criteria.mapEachCriterion(addingExtraType());

            // then there is nothing to map, so the same instance is returned
            assertThat(result).isSameAs(criteria);
        }

        @Test
        void identityMapperReturnsTheSameInstance() {
            // given a type-filtered criteria
            EventCriteria criteria = EventCriteria.havingAnyTag().andBeingOneOfTypes(ONE_TYPE);

            // when mapping every criterion to itself
            EventCriteria result = criteria.mapEachCriterion(criterion -> criterion);

            // then nothing changed, so the same instance is returned (zero-cost)
            assertThat(result).isSameAs(criteria);
        }

        @Test
        void identityMapperOnAnOrCriteriaReturnsTheSameInstance() {
            // given an OR of two type-filtered criteria
            EventCriteria criteria = EventCriteria.havingAnyTag().andBeingOneOfTypes(ONE_TYPE)
                                                  .or(EventCriteria.havingAnyTag().andBeingOneOfTypes(OTHER_TYPE));

            // when mapping every criterion to itself
            EventCriteria result = criteria.mapEachCriterion(criterion -> criterion);

            // then nothing changed, so the same instance is returned
            assertThat(result).isSameAs(criteria);
        }
    }

    @Nested
    class Rewriting {

        @Test
        void typeAddingMapperBroadensTheTypeFilter() {
            // given a criteria filtering on a single type
            EventCriteria criteria = EventCriteria.havingAnyTag().andBeingOneOfTypes(ONE_TYPE);

            // when widening every criterion with an extra type
            EventCriteria result = criteria.mapEachCriterion(addingExtraType());

            // then the flattened criterion matches both the original and the added type
            assertThat(result.flatten())
                    .singleElement()
                    .satisfies(criterion -> assertThat(criterion.types())
                            .containsExactlyInAnyOrder(ONE_TYPE, EXTRA_TYPE));
        }

        @Test
        void tagsArePreservedWhenTypesAreRewritten() {
            // given a criteria filtering on a tag and a type
            EventCriteria criteria = EventCriteria.havingTags(TAG).andBeingOneOfTypes(ONE_TYPE);

            // when widening every criterion with an extra type
            EventCriteria result = criteria.mapEachCriterion(addingExtraType());

            // then the tag survives alongside the widened types
            assertThat(result.flatten())
                    .singleElement()
                    .satisfies(criterion -> {
                        assertThat(criterion.types()).containsExactlyInAnyOrder(ONE_TYPE, EXTRA_TYPE);
                        assertThat(criterion.tags()).containsExactly(TAG);
                    });
        }

        @Test
        void eachCriterionInAnOrIsMappedIndependently() {
            // given an OR of two type-filtered criteria, only one of which the mapper rewrites
            EventCriteria criteria = EventCriteria.havingAnyTag().andBeingOneOfTypes(ONE_TYPE)
                                                  .or(EventCriteria.havingAnyTag().andBeingOneOfTypes(OTHER_TYPE));

            // when only the ONE_TYPE criterion is widened
            EventCriteria result = criteria.mapEachCriterion(criterion ->
                    criterion.types().contains(ONE_TYPE)
                            ? EventCriteria.havingAnyTag().andBeingOneOfTypes(ONE_TYPE, EXTRA_TYPE)
                            : criterion);

            // then the rewritten criterion gains the extra type while the other is left alone
            assertThat(result.flatten())
                    .anySatisfy(criterion -> assertThat(criterion.types())
                            .containsExactlyInAnyOrder(ONE_TYPE, EXTRA_TYPE))
                    .anySatisfy(criterion -> assertThat(criterion.types()).containsExactly(OTHER_TYPE));
        }

        @Test
        void mappingTypedCriterionToTagsOnlyDropsTheTypeFilter() {
            // given a criteria filtering on a tag and a type
            EventCriteria criteria = EventCriteria.havingTags(TAG).andBeingOneOfTypes(ONE_TYPE);

            // when dropping the type filter, keeping only the tags
            EventCriteria result = criteria.mapEachCriterion(criterion -> EventCriteria.havingTags(criterion.tags()));

            // then the type filter is gone but the tag restriction remains
            assertThat(result.flatten())
                    .singleElement()
                    .satisfies(criterion -> {
                        assertThat(criterion.types()).isEmpty();
                        assertThat(criterion.tags()).containsExactly(TAG);
                    });
        }

        @Test
        void mappingEveryCriterionToMatchAnythingCollapsesToNoCriteria() {
            // given a tagless type-filtered criteria
            EventCriteria criteria = EventCriteria.havingAnyTag().andBeingOneOfTypes(ONE_TYPE);

            // when mapping the criterion to a match-anything criteria
            EventCriteria result = criteria.mapEachCriterion(criterion -> EventCriteria.havingAnyTag());

            // then nothing restricts the read anymore
            assertThat(result.hasCriteria()).isFalse();
        }
    }

    @Nested
    class Validation {

        @Test
        void nullMapperIsRejected() {
            // given a type-filtered criteria
            EventCriteria criteria = EventCriteria.havingAnyTag().andBeingOneOfTypes(ONE_TYPE);

            // when / then mapping with a null mapper is rejected
            assertThatNullPointerException().isThrownBy(() -> criteria.mapEachCriterion(null));
        }

        @Test
        void mapperReturningNullIsRejected() {
            // given a type-filtered criteria
            EventCriteria criteria = EventCriteria.havingAnyTag().andBeingOneOfTypes(ONE_TYPE);

            // when / then a mapper returning null for a criterion is rejected
            assertThatNullPointerException()
                    .isThrownBy(() -> criteria.mapEachCriterion(alwaysNull()));
        }
    }
}
