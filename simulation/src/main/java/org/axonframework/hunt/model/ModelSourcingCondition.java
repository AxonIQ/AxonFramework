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
 * The condition a sourcing read is performed under: where to start, and which boundary to read.
 *
 * @param start    the first position to read from; negative values are clamped to the start of the stream
 * @param criteria the boundary to read; empty reads every event
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record ModelSourcingCondition(long start, Set<ModelCriterion> criteria) {

    /**
     * Compact constructor defensively copying the criteria.
     */
    public ModelSourcingCondition {
        criteria = Set.copyOf(Objects.requireNonNull(criteria, "The criteria cannot be null."));
    }

    /**
     * Creates a condition reading the given boundary from the start of the stream.
     *
     * @param criteria the boundary to read
     * @return the sourcing condition
     */
    public static ModelSourcingCondition conditionFor(Set<ModelCriterion> criteria) {
        return new ModelSourcingCondition(0L, criteria);
    }
}
