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

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlingExceptionHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

/**
 * Handles exceptions thrown by an {@link EventHandler} within an {@link EventHandlingComponent}.
 * <p>
 * Return {@link MessageStream#empty()} to suppress the exception — event processing continues as if no error occurred.
 * Return {@link MessageStream#failed(Throwable)} (with the same or a different exception) to let the error propagate
 * to the event processor.
 * <p>
 * Register via
 * {@link org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer.CompletePhase#withExceptionHandler(org.axonframework.common.configuration.ComponentBuilder)}.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
@FunctionalInterface
public interface EventHandlingExceptionHandler extends MessageHandlingExceptionHandler<EventMessage> {

    /**
     * Called when an event handler throws an exception.
     *
     * @param event   the event being handled when the exception occurred
     * @param context the active processing context
     * @param error   the exception thrown by the event handler
     * @return {@link MessageStream#empty()} to suppress the error, or {@link MessageStream#failed(Throwable)} to
     *         propagate it
     */
    @Override
    MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context, Throwable error);
}
