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

package org.axonframework.messaging.eventhandling;

import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;

/**
 * Represents an {@link EventMessage} containing a {@link TrackingToken}. The tracking token can be used be
 * {@link EventProcessor event processor} to keep track of which events it has processed.
 *
 * @param <T> The type of payload contained in this Message
 * @author Rene de Waele
 * @deprecated In favor of returning entries that contain a token and event message separately
 */
@Deprecated
public interface TrackedEventMessage extends EventMessage {

    /**
     * Returns the {@link TrackingToken} of the event message.
     *
     * @return the tracking token of the event
     */
    TrackingToken trackingToken();

    /**
     * Creates a copy of this message with the given {@code trackingToken} to replace the one in this message.
     * <p>
     * This method is useful in case streams are modified (combined, split), and the tokens of the combined stream are
     * different than the originating stream.
     *
     * @param trackingToken The tracking token to replace
     * @return a new instance of a message with a different tracking token
     */
    TrackedEventMessage withTrackingToken(TrackingToken trackingToken);
}
