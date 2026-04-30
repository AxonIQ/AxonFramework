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
 * The default {@link SourcingCondition} implementation.
 * <p>
 * The {@code start} refers to the start point of the event stream that is of interest to this
 * {@link SourcingCondition}.
 *
 * @param sourcingStrategy the {@link SourcingStrategy} used to construct the message stream
 * @param criteria the {@link EventCriteria} set of the entity to source
 * @author Steven van Beelen
 * @author John Hendrikx
 * @since 5.0.0
 */
record DefaultSourcingCondition(
        SourcingStrategy sourcingStrategy,
        EventCriteria criteria
) implements SourcingCondition {

    DefaultSourcingCondition {
        requireNonNull(sourcingStrategy, "sourcingStrategy cannot be null");
        requireNonNull(criteria, "criteria cannot be null");
    }

    @Override
    public SourcingCondition or(SourcingCondition other) {
        return new DefaultSourcingCondition(
            other.sourcingStrategy().merge(sourcingStrategy),
            other.criteria().or(criteria)
        );
    }

    @Override
    public Position start() {
        return switch (sourcingStrategy) {
            case SourcingStrategy.Absolute(Position p) -> p;
            default -> throw new IllegalStateException("No start position is available, please use sourcingStrategy method");
        };
    }
}
