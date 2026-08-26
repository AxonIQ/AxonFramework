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

package org.axonframework.messaging;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.GenericResultMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

/**
 * Interface for a component that processes Messages.
 *
 * @param <T> The message type this handler can process
 * @author Rene de Waele
 * @since 3.0
 */
public interface LegacyMessageHandler<T extends Message, R extends Message> {

    /**
     * Handles the given {@code message}.
     *
     * @param message The message to be processed.
     * @return The result of the message processing.
     * @throws Exception any exception that occurs during message handling
     * @deprecated In favor of {@link #handle(Message, ProcessingContext)}
     */
    // TODO Remove entirely once #3065, #3195, #3517, and #3728 have been resolved.
    @Deprecated
    @Internal
    Object handleSync(T message, ProcessingContext context) throws Exception;

    /**
     * Handles the given {@code message} and returns a {@link MessageStream} containing the result of the processing.
     *
     * @param message The message to be processed.
     * @param context The {@code ProcessingContext} in which the reset is being prepared.
     * @return A {@link MessageStream} containing the result of the message processing.
     */
    default MessageStream<R> handle(T message, ProcessingContext context) {
        try {
            var result = handleSync(message, context);
            if (result instanceof MessageStream<?> messageStream) {
                return (MessageStream<R>) messageStream;
            }
            return MessageStream.just((R) GenericResultMessage.asResultMessage(result));
        } catch (Exception e) {
            return MessageStream.failed(e);
        }
    }

    /**
     * Indicates whether this handler can handle the given message
     *
     * @param message The message to verify
     * @return {@code true} if this handler can handle the message, otherwise {@code false}
     */
    @Deprecated
    default boolean canHandle(T message, ProcessingContext context) {
        return true;
    }

    /**
     * Returns the instance type that this handler delegates to.
     *
     * @return Returns the instance type that this handler delegates to
     */
    @Deprecated
    default Class<?> getTargetType() {
        return getClass();
    }

    /**
     * Indicates whether this handler can handle messages of given type
     *
     * @param payloadType The payloadType to verify
     * @return {@code true} if this handler can handle the payloadType, otherwise {@code false}
     */
    @Deprecated
    default boolean canHandleType(Class<?> payloadType) {
        return true;
    }
}
