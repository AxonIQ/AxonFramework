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

import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;

/**
 * An implementation of the {@link StreamingCondition} that will start
 * {@link StreamableEventSource#open(StreamingCondition) streaming} from the given {@code position}.
 * <p>
 * A {@code null} {@code position} is normalized to {@link TrackingToken#FIRST}, so {@link #position()} itself never
 * returns {@code null}.
 *
 * @param position The {@link TrackingToken} describing the position to start streaming from, or {@code null} to
 *                  start from {@link TrackingToken#FIRST}.
 * @author Steven van Beelen
 * @since 5.0.0
 */
record StartingFrom(@Nullable TrackingToken position) implements StreamingCondition {

    StartingFrom {
        position = position != null ? position : TrackingToken.FIRST;
    }

    /*
     * Explicitly overridden so the accessor does not inherit the @Nullable annotation carried by the position
     * record component below, which is only there to let the compact constructor accept a null input.
     */
    @Override
    public TrackingToken position() {
        return position;
    }

    @Override
    public StreamingCondition withCriteria(EventCriteria criteria) {
        return new DefaultStreamingCondition(position, criteria);
    }

    @Override
    public StreamingCondition or(EventCriteria criteria) {
        return new DefaultStreamingCondition(position, criteria);
    }
}
