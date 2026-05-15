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

import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.Nullable;


/**
 * Interface describing the condition to {@link StreamableEventSource#open(StreamingCondition,
 * org.axonframework.messaging.core.unitofwork.ProcessingContext) stream} from a streamable
 * event source (like an Event Store).
 * <p>
 * This condition has a mandatory {@link #position()} that dictates from what point streaming should commence.
 * Additionally, an {@link #criteria()} can be set to filter the stream of events.
 *
 * @author Michal Negacz
 * @author Milan Savić
 * @author Marco Amann
 * @author Sara Pellegrini
 * @author Steven van Beelen
 * @since 5.0.0
 */
public sealed interface StreamingCondition extends EventsCondition permits DefaultStreamingCondition, StartingFrom {

    /**
     * Constructs a simple {@code  StreamingCondition} that starts streaming from the given {@code position}. When the
     * {@code position} is {@code null} streaming will start from the beginning of the Event Store.
     *
     * @param position The {@link TrackingToken} describing the position to start streaming from.
     * @return A simple {@code  StreamingCondition} that starts streaming from the given {@code position}.
     */
    static StreamingCondition startingFrom(@Nullable TrackingToken position) {
        return new StartingFrom(position);
    }

    /**
     * Constructs a simple {@code  StreamingCondition} that starts streaming from the given {@code position}, only
     * returning events matching the given {@code criteria}.
     *
     * @param position The {@link TrackingToken} describing the position to start streaming from.
     * @param criteria The criteria used to match events that are to be streamed.
     * @return A simple {@code  StreamingCondition} that starts streaming from the given {@code position}, only
     * returning events matching the given {@code criteria}.
     */
    static StreamingCondition conditionFor(TrackingToken position,
                                           EventCriteria criteria) {
        return new DefaultStreamingCondition(position, criteria);
    }

    /**
     * The position as a {@link TrackingToken} to start streaming from.
     *
     * @return The position as a {@link TrackingToken} to start streaming from.
     */
    TrackingToken position();

    @Override
    default EventCriteria criteria() {
        return EventCriteria.havingAnyTag();
    }

    /**
     * Combines the {@link #criteria()} of {@code this} {@code  StreamingCondition} with the given {@code criteria}.
     *
     * @param criteria The {@link EventCriteria} to {@link EventCriteria#or()} with the {@link #criteria()} of
     *                 {@code this} {@code  StreamingCondition}.
     * @return A {@code  StreamingCondition} that {@link EventCriteria#or() "or-ed"} the given {@code criteria} with the
     * {@link #criteria()} of {@code this} {@code  StreamingCondition}.
     */
    StreamingCondition or(EventCriteria criteria);
}
