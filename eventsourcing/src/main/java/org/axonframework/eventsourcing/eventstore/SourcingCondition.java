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
import org.axonframework.messaging.eventstreaming.EventsCondition;

/**
 * Interface describing the condition to {@link EventStoreTransaction#source(SourcingCondition) source} events from an
 * Event Store.
 * <p>
 * The condition has a mandatory {@link #criteria()} used to retrieve the exact sequence of events to source the
 * model(s). The {@link #start()} operation defines the start point when sourcing events that the
 * {@link EventStoreTransaction} is interested in.
 *
 * @author Michal Negacz
 * @author Milan Savić
 * @author Marco Amann
 * @author Sara Pellegrini
 * @author Steven van Beelen
 * @auhtor John Hendrikx
 * @since 5.0.0
 */
public sealed interface SourcingCondition extends EventsCondition permits DefaultSourcingCondition {

    /**
     * Construct a {@code SourcingCondition} used to source a model based on the given {@code criteria}.
     *
     * @param criteria the {@link EventCriteria} used as the {@link SourcingCondition#criteria()}
     * @return a {@code SourcingCondition} that will retrieve an event sequence matching the given {@code criteria}
     * @throws NullPointerException if any argument is {@code null}
     */
    static SourcingCondition conditionFor(EventCriteria criteria) {
        return conditionFor(Position.START, criteria);
    }

    /**
     * Construct a {@code SourcingCondition} used to source a model based on the given {@code criteria}.
     * <p>
     * Will start the sequence at the given absolute {@code position} value using the
     * {@link SourcingStrategy.Absolute absolute sourcing strategy}.
     *
     * @param position the position to use for the {@link SourcingStrategy.Absolute absolute sourcing strategy}
     * @param criteria the {@link EventCriteria} used as the {@link SourcingCondition#criteria()}
     * @return a {@code SourcingCondition} that will retrieve an event sequence matching the given {@code criteria},
     *     starting at the given {@code start}
     * @throws NullPointerException if any argument is {@code null}
     */
    static SourcingCondition conditionFor(Position position, EventCriteria criteria) {
        return new DefaultSourcingCondition(new SourcingStrategy.Absolute(position), criteria);
    }

    /**
     * Construct a {@code SourcingCondition} used to source a model based on the given {@code criteria}
     * and {@link SourcingStrategy sourcing strategy}.
     *
     * @param sourcingStrategy the {@link SourcingStrategy} used to construct the message stream
     * @param criteria         the {@link EventCriteria} used as the {@link SourcingCondition#criteria()}
     * @return a {@code SourcingCondition} that will retrieve an event sequence matching the given {@code criteria},
     *     starting at the given {@code start}
     * @throws NullPointerException if any argument is {@code null}
     */
    static SourcingCondition conditionFor(SourcingStrategy sourcingStrategy, EventCriteria criteria) {
        return new DefaultSourcingCondition(sourcingStrategy, criteria);
    }

    /**
     * The start position in the event sequence to source. Defaults to {@code Position.START} to ensure we start at the beginning
     * of the sequence's stream complying to the {@link #criteria()}.
     *
     * @return the start position in the event sequence to source, never {@code null}
     * @deprecated use {@link #sourcingStrategy()}'s and check for the {@link SourcingStrategy.Absolute absolute mode}
     */
    @Deprecated(since = "5.1.0", forRemoval = true)
    default Position start() {
        return Position.START;
    }

    /**
     * The sourcing strategy used for this sourcing condition.
     *
     * @return a {@link SourcingStrategy}, never {@code null}
     */
    default SourcingStrategy sourcingStrategy() {
        return new SourcingStrategy.Absolute(Position.START);
    }

    /**
     * Merges {@code this SourcingCondition} with the given {@code other SourcingCondition}, by combining their
     * {@link #criteria() search criteria} and {@link #sourcingStrategy() sourcing strategies}.
     * <p>
     * Warning: if the sourcing strategies cannot be combined, this method will fail with an {@link UnsupportedOperationException}.
     * If both sourcing strategies use {@link SourcingStrategy.Absolute absolute positioning}, then the merged condition
     * might return some events that neither of the original conditions would have returned on their own.
     * <p>
     * For positional strategies, usually the earlier starting point (minimum start value) will be used in the final merged condition.
     *
     * @param other the {@code SourcingCondition} to combine with {@code this SourcingCondition}
     * @return a combined {@code SourcingCondition} based on {@code this SourcingCondition} and the given {@code other}
     * @throws NullPointerException if any argument is {@code null}
     * @throws UnsupportedOperationException if the strategies are uncombinable
     */
    SourcingCondition or(SourcingCondition other);
}
