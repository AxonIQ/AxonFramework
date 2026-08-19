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
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that splitting a criteria with {@link EventCriteria#flatten()} and rejoining it with
 * {@link EventCriteria#either(java.util.Collection)} matches the exact same events. This round-trip is what
 * {@link EventCriteria#mapEachCriterion(java.util.function.Function)} relies on internally.
 * <p>
 * The one exception is a match-anything criteria: it flattens to nothing, which {@code either(...)} cannot rebuild.
 * That is why {@code mapEachCriterion} returns the original instance when the flattening is empty, verified separately below.
 */
class EventCriteriaFlattenRoundTripTest {

    private static final QualifiedName ONE_TYPE = new QualifiedName("OneType");
    private static final QualifiedName OTHER_TYPE = new QualifiedName("OtherType");
    private static final QualifiedName UNRELATED_TYPE = new QualifiedName("Unrelated");
    private static final Tag TAG_1 = new Tag("key1", "value1");
    private static final Tag TAG_2 = new Tag("key2", "value2");

    /**
     * A representative sample of (type, tags) combinations to probe whether two criteria agree on matching.
     */
    private static final List<Set<Tag>> SAMPLE_TAG_SETS =
            List.of(Set.of(), Set.of(TAG_1), Set.of(TAG_2), Set.of(TAG_1, TAG_2));
    private static final List<QualifiedName> SAMPLE_TYPES = List.of(ONE_TYPE, OTHER_TYPE, UNRELATED_TYPE);

    /**
     * Asserts that {@code either(original.flatten())} matches the exact same sample events as {@code original}.
     */
    private static void assertRoundTripPreservesMatching(EventCriteria original) {
        EventCriteria rebuilt = EventCriteria.either(new HashSet<>(original.flatten()));
        for (QualifiedName type : SAMPLE_TYPES) {
            for (Set<Tag> tags : SAMPLE_TAG_SETS) {
                assertThat(rebuilt.matches(type, tags))
                        .as("matching of type %s with tags %s after flatten round-trip", type, tags)
                        .isEqualTo(original.matches(type, tags));
            }
        }
    }

    @Test
    void typeOnlyCriteriaSurvivesTheRoundTrip() {
        // given a type-only criteria
        EventCriteria criteria = EventCriteria.havingAnyTag().andBeingOneOfTypes(ONE_TYPE);

        // when / then flattening and rebuilding preserve which events match
        assertRoundTripPreservesMatching(criteria);
    }

    @Test
    void tagOnlyCriteriaSurvivesTheRoundTrip() {
        // given a tag-only criteria
        EventCriteria criteria = EventCriteria.havingTags(TAG_1).andBeingOfAnyType();

        // when / then flattening and rebuilding preserve which events match
        assertRoundTripPreservesMatching(criteria);
    }

    @Test
    void tagAndTypeCriteriaSurvivesTheRoundTrip() {
        // given a criteria filtering on both a tag and a type
        EventCriteria criteria = EventCriteria.havingTags(TAG_1).andBeingOneOfTypes(ONE_TYPE);

        // when / then flattening and rebuilding preserve which events match
        assertRoundTripPreservesMatching(criteria);
    }

    @Test
    void orCombinedCriteriaSurvivesTheRoundTrip() {
        // given an OR of two distinct tag-and-type criteria
        EventCriteria criteria = EventCriteria.havingTags(TAG_1).andBeingOneOfTypes(ONE_TYPE)
                                              .or().havingTags(TAG_2).andBeingOneOfTypes(OTHER_TYPE);

        // when / then flattening and rebuilding preserve which events match
        assertRoundTripPreservesMatching(criteria);
    }

    @Test
    void nestedOrCriteriaSurvivesTheRoundTrip() {
        // given a nested OR of three criteria
        EventCriteria criteria = EventCriteria.havingTags(TAG_1).andBeingOneOfTypes(ONE_TYPE)
                                              .or(EventCriteria.havingTags(TAG_2).andBeingOneOfTypes(OTHER_TYPE)
                                                               .or(EventCriteria.havingAnyTag()
                                                                                .andBeingOneOfTypes(UNRELATED_TYPE)));

        // when / then flattening and rebuilding preserve which events match
        assertRoundTripPreservesMatching(criteria);
    }

    @Test
    void matchAnythingCriteriaIsTheRoundTripExceptionThatMapCriteriaGuardsAgainst() {
        // given a match-anything criteria, which flattens to no criterion
        EventCriteria matchAll = EventCriteria.havingAnyTag();

        // when it is flattened and naively reassembled
        EventCriteria rebuilt = EventCriteria.either(new HashSet<>(matchAll.flatten()));

        // then the round-trip does NOT preserve meaning: the original matches everything, the rebuild nothing.
        // This is exactly why mapEachCriterion short-circuits an empty flattening and returns the original instance.
        assertThat(matchAll.matches(ONE_TYPE, Set.of())).isTrue();
        assertThat(rebuilt.matches(ONE_TYPE, Set.of())).isFalse();
        assertThat(matchAll.mapEachCriterion(criterion -> criterion)).isSameAs(matchAll);
    }
}
