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

import static java.util.Objects.requireNonNull;

/**
 * Default implementation of the {@link AppendCondition}, using the given {@code consistencyMarker} and {@code criteria}
 * as output for the {@link #consistencyMarker()} and {@link #criteria()} operations respectively.
 *
 * @param consistencyMarker The consistency marker obtained while sourcing events.
 * @param criteria          The criteria set defining which changes are considered conflicting.
 * @author Steven van Beelen
 * @since 5.0.0
 */
record DefaultAppendCondition(
        ConsistencyMarker consistencyMarker,
        EventCriteria criteria
) implements AppendCondition {

    DefaultAppendCondition {
        requireNonNull(consistencyMarker, "The ConsistencyMarker cannot be null");
        requireNonNull(criteria, "The EventCriteria cannot be null");
    }

    @Override
    public AppendCondition withMarker(ConsistencyMarker consistencyMarker) {
        if (this.consistencyMarker.equals(consistencyMarker)) {
            return this;
        }
        return new DefaultAppendCondition(consistencyMarker, criteria);
    }

    @Override
    public AppendCondition replaceCriteria(EventCriteria criteria) {
        return this.criteria.equals(criteria)
                ? this
                : new DefaultAppendCondition(consistencyMarker, criteria);
    }
}
