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

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;

/**
 * Empty {@link GenericEventMessage} implementation without any {@link EventMessage#payload() payload}, used as a
 * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken} progress marker for the
 * {@link org.axonframework.messaging.core.MessageStream} in absence of any events that matched the
 * {@link StreamingCondition}.
 * <p>
 * Required to be paired with {@link org.axonframework.messaging.core.Context} containing the {@code TrackingToken} to
 * signal the actual progress that's been made.
 * <p>
 * Emitting this marker into the stream allows event consumers to progress the stored token to the furthest scanned
 * position even when no events matched the criteria. This prevents consumers from remaining frozen at an earlier
 * position when large gaps of non-matching events exist, and avoids re-scanning the same gap from scratch on processor
 * restart.
 *
 * @author Steven van Beelen
 * @since 5.1.2
 */
@Internal
public class TokenProgressMessage extends GenericEventMessage {

    /**
     * The sole instance of the {@link TokenProgressMessage}.
     */
    public static final TokenProgressMessage INSTANCE = new TokenProgressMessage();

    private TokenProgressMessage() {
        super(new MessageType(TokenProgressMessage.class), null);
    }
}
