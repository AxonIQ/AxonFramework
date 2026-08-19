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

package org.axonframework.messaging.core;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;

/**
 * Handles exceptions thrown by a message handler.
 * <p>
 * Return {@link MessageStream#empty()} to suppress the exception. Return {@link MessageStream#failed(Throwable)} (with
 * the same or a different exception) to let the error propagate to the message processor.
 *
 * @param <M> the type of message being handled
 * @author Allard Buijze
 * @since 5.2.0
 */
@FunctionalInterface
public interface MessageHandlingExceptionHandler<M extends Message> {

    /**
     * Called when a message handler throws an exception.
     *
     * @param message the message being handled when the exception occurred
     * @param context the active processing context
     * @param error   the exception thrown by the message handler
     * @return {@link MessageStream#empty()} to suppress the error, or {@link MessageStream#failed(Throwable)} to
     *         propagate it
     */
    MessageStream<? extends Message> handle(M message, ProcessingContext context, Throwable error);
}
