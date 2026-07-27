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

package org.axonframework.hunt.model;

import java.util.Objects;
import java.util.Set;

/**
 * One non-nested criterion of a Dynamic Consistency Boundary: a set of tags and an optional set of types.
 * <p>
 * A criterion is the AND half of the protocol. An event matches it only when it carries <em>every</em> tag the
 * criterion names, and when its type is one the criterion names, if it names any at all.
 * <p>
 * The OR half lives one level up: a boundary is a {@code Set} of criteria, and matching that set is
 * {@link #anyMatches(Set, ModelEvent)}. An <em>empty</em> set is the match-everything boundary, mirroring the
 * framework, where a criteria with no tags and no types flattens to no criteria and matches every event.
 *
 * @param types the event type names this criterion accepts; empty accepts any type
 * @param tags  the tags an event must all carry to match
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record ModelCriterion(Set<String> types, Set<ModelTag> tags) {

    /**
     * Compact constructor rejecting missing parts and defensively copying both sets.
     */
    public ModelCriterion {
        types = Set.copyOf(Objects.requireNonNull(types, "The types cannot be null."));
        tags = Set.copyOf(Objects.requireNonNull(tags, "The tags cannot be null."));
    }

    /**
     * Creates a criterion restricted by tags only, accepting any event type.
     *
     * @param tags the tags an event must all carry to match
     * @return the criterion
     */
    public static ModelCriterion havingTags(ModelTag... tags) {
        return new ModelCriterion(Set.of(), Set.of(tags));
    }

    /**
     * Creates a criterion restricted by both tags and types.
     *
     * @param types the event type names to accept
     * @param tags  the tags an event must all carry to match
     * @return the criterion
     */
    public static ModelCriterion havingTagsAndTypes(Set<ModelTag> tags, Set<String> types) {
        return new ModelCriterion(types, tags);
    }

    /**
     * Indicates whether the given event satisfies this criterion.
     * <p>
     * Rule {@code CriterionTagsMatchByContainsAll}: the event's tags must be a superset of this criterion's tags.
     * Rule {@code CriterionTypesMatchByMembershipOrAnyWhenEmpty}: the event's type must be one of this criterion's
     * types, unless this criterion names none.
     *
     * @param event the event to test
     * @return {@code true} when the event satisfies both the type and the tag restriction
     */
    public boolean matches(ModelEvent event) {
        Objects.requireNonNull(event, "The event cannot be null.");
        return (types.isEmpty() || types.contains(event.type())) && event.tags().containsAll(tags);
    }

    /**
     * Indicates whether the given event satisfies a Dynamic Consistency Boundary.
     * <p>
     * Rule {@code CriteriaMatchIsDisjunctionOverCriteria}: an event matches the boundary when it satisfies any one of
     * its criteria. An empty set of criteria is the match-everything boundary and accepts every event.
     *
     * @param criteria the boundary's criteria; empty means match everything
     * @param event    the event to test
     * @return {@code true} when the event matches the boundary
     */
    public static boolean anyMatches(Set<ModelCriterion> criteria, ModelEvent event) {
        Objects.requireNonNull(criteria, "The criteria cannot be null.");
        return criteria.isEmpty() || criteria.stream().anyMatch(criterion -> criterion.matches(event));
    }
}
