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
 * The condition an append is validated against: a consistency marker and a Dynamic Consistency Boundary.
 * <p>
 * The marker is modelled as a plain position, using the same encoding the framework's global-index marker resolves
 * to: {@link DcbStoreModel#ORIGIN} for the start of the stream, {@link DcbStoreModel#INFINITY} for its end, and
 * otherwise the position itself. Encoding it as a number rather than an object keeps the model comparable against
 * both a storage engine and a TLA+ specification.
 *
 * @param marker   the position after which matching events are conflicts
 * @param criteria the boundary the append claims; empty means every event is in scope
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record ModelAppendCondition(long marker, Set<ModelCriterion> criteria) {

    /**
     * Compact constructor defensively copying the criteria.
     */
    public ModelAppendCondition {
        criteria = Set.copyOf(Objects.requireNonNull(criteria, "The criteria cannot be null."));
    }

    /**
     * The condition that claims no boundary at all: appended events take part in nobody's consistency boundary, so no
     * event can conflict with them.
     * <p>
     * Its marker is {@link DcbStoreModel#INFINITY}, which alone is enough to disable conflict detection.
     *
     * @return an append condition that is always legal
     */
    public static ModelAppendCondition none() {
        return new ModelAppendCondition(DcbStoreModel.INFINITY, Set.of());
    }

    /**
     * The condition claiming the given boundary from the start of the stream: every event already stored that matches
     * the boundary is a conflict.
     *
     * @param criteria the boundary to claim
     * @return an append condition anchored at {@link DcbStoreModel#ORIGIN}
     */
    public static ModelAppendCondition withCriteria(Set<ModelCriterion> criteria) {
        return new ModelAppendCondition(DcbStoreModel.ORIGIN, criteria);
    }

    /**
     * Returns a copy of this condition anchored at the given marker.
     *
     * @param newMarker the position after which matching events become conflicts
     * @return a copy of this condition carrying the given marker
     */
    public ModelAppendCondition withMarker(long newMarker) {
        return new ModelAppendCondition(newMarker, criteria);
    }
}
