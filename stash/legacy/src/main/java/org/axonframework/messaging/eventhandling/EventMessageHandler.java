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

import java.util.Objects;
import java.util.Set;


import org.axonframework.messaging.LegacyMessageHandler;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

/**
 * Interface to be implemented by classes that can handle events.
 *
 * @author Allard Buijze
 * @see EventBus
 * @see DomainEventMessage
 * @see EventHandler
 * @since 0.1
 * @deprecated Replace in favor of org.axonframework.messaging.eventhandling.EventHandler
 */
@Deprecated // Replace in favor of org.axonframework.messaging.eventhandling.EventHandler
public interface EventMessageHandler extends LegacyMessageHandler<EventMessage, Message> {

    /**
     * Process the given event. The implementation may decide to process or skip the given event. It is highly
     * unrecommended to throw any exception during the event handling process.
     *
     * @param event the event to handle
     * @return the result of the event handler invocation. Is generally ignored
     * @throws Exception when an exception is raised during event handling
     */
    Object handleSync(EventMessage event, ProcessingContext context) throws Exception;

    default MessageStream<Message> handle(EventMessage event, ProcessingContext context) {
        try {
            handleSync(event, context);
            return MessageStream.empty();
        } catch (Exception e) {
            return MessageStream.failed(e);
        }
    }

    /**
     * Performs any activities that are required to reset the state managed by handlers assigned to this handler.
     *
     * @param context the {@code ProcessingContext} in which the reset is being performed.
     */
    default void prepareReset(ProcessingContext context) {
    }

    /**
     * Performs any activities that are required to reset the state managed by handlers assigned to this handler.
     *
     * @param <R>               the type of the provided {@code resetContext}
     * @param resetContext      a {@code R} used to support the reset operation
     * @param context the {@code ProcessingContext} in which the reset is being performed.
     */
    default <R> void prepareReset(R resetContext, ProcessingContext context) {
        if (Objects.isNull(resetContext)) {
            prepareReset(context);
        } else {
            throw new UnsupportedOperationException(
                    "EventMessageHandler#prepareReset(R) is not implemented for a non-null reset context."
            );
        }
    }

    /**
     * Indicates whether the handlers managed by this invoker support a reset.
     *
     * @return {@code true} if a reset is supported, otherwise {@code false}
     */
    default boolean supportsReset() {
        return true;
    }

    /**
     * Returns the set of event payload types that this handler can handle.
     * <p>
     * The default implementation returns an empty set, indicating that the handler
     * does not provide information about supported event types. Implementations should
     * override this method to provide accurate type information.
     *
     * @return A set of classes representing the event payload types this handler can handle.
     * @since 5.0.0
     */
    default Set<Class<?>> supportedEventTypes() {
        return Set.of();
    }
}
