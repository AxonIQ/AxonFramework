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

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.EventsCondition;

/**
 * Interface describing the consistency boundary condition for
 * {@link EventMessage EventMessages} when
 * {@link EventStoreTransaction#appendEvent(EventMessage) appending} them to an Event Store.
 *
 * @author Michal Negacz
 * @author Milan Savić
 * @author Marco Amann
 * @author Sara Pellegrini
 * @author Steven van Beelen
 * @author Allard Buijze
 * @since 5.0.0
 */
public sealed interface AppendCondition extends EventsCondition permits NoAppendCondition, DefaultAppendCondition {

    /**
     * Returns an {@code AppendCondition} that has no criteria nor consistency marker.
     * <p>
     * Only use this {@code AppendCondition} when appending events that <em>do not</em> partake in the consistency
     * boundary of any model(s).
     *
     * @return An {@code AppendCondition} that has no criteria nor consistency marker.
     */
    static AppendCondition none() {
        return NoAppendCondition.INSTANCE;
    }

    /**
     * Creates an AppendCondition to append events only if no events matching given {@code criteria} are available.
     *
     * @param criteria The criteria for the AppendCondition.
     * @return A condition that matches against given criteria.
     */
    static AppendCondition withCriteria(EventCriteria criteria) {
        return new DefaultAppendCondition(ConsistencyMarker.ORIGIN, criteria);
    }

    /**
     * Returns an AppendCondition with a condition that represents this AppendCondition's criteria or the given
     * {@code criteria}.
     *
     * @param criteria The additional criteria the condition may match against.
     * @return an AppendCondition that combined this condition's criteria and the given, using 'OR' semantics.
     */
    default AppendCondition orCriteria(EventCriteria criteria) {
        return new DefaultAppendCondition(this.consistencyMarker(), this.criteria().or(criteria));
    }

    /**
     * Returns the position in the event store until which the {@link #criteria()} should be validated against.
     * <p>
     * Appending will fail when there are events appended after this point that match the provided
     * {@link EventCriteria}.
     *
     * @return The position in the event store until which the {@link #criteria()} should be validated against.
     */
    ConsistencyMarker consistencyMarker();

    /**
     * Creates an AppendCondition with the same criteria as this one, but with given {@code consistencyMarker}.
     *
     * @param consistencyMarker The consistency marker for the new {@code this AppendCondition}.
     * @return An {@code AppendCondition} with the given {@code consistencyMarker}.
     */
    AppendCondition withMarker(ConsistencyMarker consistencyMarker);

    /**
     * Creates an {@code AppendCondition} with the same {@link #consistencyMarker()} as this one, but with the given
     * {@code criteria}, replacing any previously set criteria.
     *
     * @param criteria the {@link EventCriteria} for the new {@code AppendCondition}
     * @return an {@code AppendCondition} with the given {@code criteria}
     */
    default AppendCondition replaceCriteria(EventCriteria criteria) {
        return new DefaultAppendCondition(this.consistencyMarker(), criteria);
    }
}
