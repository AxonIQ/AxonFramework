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

package org.axonframework.messaging.eventhandling.gateway;

import org.axonframework.common.ClockUtils;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;

/**
 * Utility class for the {@link EventGateway} and {@link EventAppender} implementations.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
class EventPublishingUtils {

    private EventPublishingUtils() {
        // Prevent instantiation as this is a utility class
    }

    /**
     * Converts the given {@code event} to an {@link EventMessage}. If the event is already an {@link EventMessage}, it
     * is returned as is. If the event is a {@link Message}, it is wrapped in a {@link GenericEventMessage}. Otherwise,
     * a new {@link GenericEventMessage} is created with the given event and the type resolved by the given
     * {@link MessageTypeResolver}.
     *
     * @param event               The event to convert.
     * @param messageTypeResolver The {@link MessageTypeResolver} to resolve the type of the event.
     * @return The event as an {@link EventMessage}.
     */
    static EventMessage asEventMessage(Object event, MessageTypeResolver messageTypeResolver) {
        if (event instanceof EventMessage e) {
            return e;
        }
        if (event instanceof Message message) {
            return new GenericEventMessage(message, ClockUtils::instant);
        }
        return new GenericEventMessage(
                messageTypeResolver.resolveOrThrow(event),
                event,
                Metadata.emptyInstance()
        );
    }

    /**
     * Converts the given {@code event} to an {@link EventMessage} with the provided {@code metadata}. If the event is
     * already an {@link EventMessage}, the provided metadata is merged with its existing metadata (provided values take
     * precedence on conflict). If the event is a {@link Message}, it is wrapped in a {@link GenericEventMessage} with
     * merged metadata. Otherwise, a new {@link GenericEventMessage} is created with the given event, the provided
     * metadata, and the type resolved by the given {@link MessageTypeResolver}.
     *
     * @param event               The event to convert.
     * @param metadata            The metadata to attach to the event.
     * @param messageTypeResolver The {@link MessageTypeResolver} to resolve the type of the event.
     * @return The event as an {@link EventMessage} with the given metadata applied.
     */
    static EventMessage asEventMessage(Object event,
                                       Metadata metadata,
                                       MessageTypeResolver messageTypeResolver) {
        if (event instanceof EventMessage e) {
            return e.andMetadata(metadata);
        }
        if (event instanceof Message message) {
            return new GenericEventMessage(message.andMetadata(metadata), ClockUtils::instant);
        }
        return new GenericEventMessage(
                messageTypeResolver.resolveOrThrow(event),
                event,
                metadata
        );
    }
}
