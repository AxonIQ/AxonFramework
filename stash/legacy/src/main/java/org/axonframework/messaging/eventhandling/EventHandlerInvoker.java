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

import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.Objects;
import java.util.Set;

/**
 * Interface for an event message handler that defers handling to one or more other handlers.
 *
 * @author Rene de Waele
 * @since 3.0
 * @deprecated In favor of the {@link EventHandlingComponent}.
 */
@Deprecated(since = "5.0.0", forRemoval = true)
public interface EventHandlerInvoker {

    /**
     * Check whether or not this invoker has handlers that can handle the given {@code eventMessage} for a given
     * {@code segment}.
     *
     * @param eventMessage The message to be processed.
     * @param context      The {@code ProcessingContext} in which the event handler will be invoked.
     * @param segment      The segment for which the event handler should be invoked.
     * @return {@code true} if the invoker has one or more handlers that can handle the given message, {@code false}
     * otherwise.
     */
    boolean canHandle(EventMessage eventMessage, ProcessingContext context, Segment segment);

    /**
     * Check whether or not this invoker has handlers that can handle the given {@code payloadType}.
     *
     * @param payloadType The payloadType of the message to be processed
     * @return {@code true} if the invoker has one or more handlers that can handle the given message, {@code false}
     * otherwise
     */
    default boolean canHandleType(Class<?> payloadType) {
        return true;
    }

    /**
     * Handle the given {@code message} for the given {@code segment}.
     * <p>
     * Callers are recommended to invoke {@link #canHandle(EventMessage, ProcessingContext, Segment)} prior to invocation, but aren't
     * required to do so. Implementations must ensure to take the given segment into account when processing messages.
     *
     * @param message The message to handle.
     * @param segment The segment for which to handle the message.
     * @throws Exception when an exception occurs while handling the message.
     */
    void handle(EventMessage message,
                ProcessingContext context,
                Segment segment) throws Exception;

    /**
     * Indicates whether the handlers managed by this invoker support a reset.
     *
     * @return {@code true} if a reset is supported, otherwise {@code false}
     */
    default boolean supportsReset() {
        return true;
    }

    /**
     * Performs any activities that are required to reset the state managed by handlers assigned to this invoker.
     *
     * @param context The {@link ProcessingContext} in which the reset is performed.
     */
    default void performReset(ProcessingContext context) {
    }

    /**
     * Performs any activities that are required to reset the state managed by handlers assigned to this invoker.
     *
     * @param <R>               The type of the provided {@code resetContext}.
     * @param resetContext      A {@code R} used to support the reset operation.
     * @param processingContext The {@link ProcessingContext} in which the reset is performed.
     */
    default <R> void performReset(@Nullable R resetContext, ProcessingContext processingContext) {
        if (Objects.isNull(resetContext)) {
            performReset(processingContext);
        } else {
            throw new UnsupportedOperationException(
                    "EventHandlerInvoker#performRest(R) is not implemented for a non-null reset context."
            );
        }
    }

    /**
     * This is a way for an event processor to communicate that a segment which was being processed is released. This
     * might be needed or required to free resources, are clean up state which is related to the {@link Segment}.
     *
     * @param segment the segment which was released.
     */
    default void segmentReleased(Segment segment) {
    }

    /**
     * Returns the set of classes representing the types of events that this invoker can handle.
     * This method is used to determine the supported events for migration to {@link EventHandlingComponent}.
     * It's introduced as a refactoring step to limit the scope.
     * The whole class will be removed during future refactoring steps.
     *
     * @return A set of classes representing the event types this invoker can handle.
     */
    default Set<Class<?>> supportedEventTypes() {
        return Set.of();
    }
}
