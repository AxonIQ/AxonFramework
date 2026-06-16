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

package org.axonframework.messaging.commandhandling;

import org.axonframework.messaging.core.MessageHandlingExceptionHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

/**
 * Handles exceptions thrown by a {@link CommandHandler} within a {@link CommandHandlingComponent}.
 * <p>
 * Return {@link MessageStream#empty()} to suppress the exception. Return {@link MessageStream#failed(Throwable)} (with
 * the same or a different exception) to let the error propagate to the caller. Return
 * {@link MessageStream#just(org.axonframework.messaging.core.Message)} with a {@link CommandResultMessage} to
 * substitute a result in place of the error.
 * <p>
 * The return type is {@link MessageStream.Single} because command handling always produces at most one result, matching
 * the contract of {@link CommandHandler#handle(CommandMessage, ProcessingContext)}.
 * <p>
 * Register via
 * {@link org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule.CommandHandlerPhase#withExceptionHandler(org.axonframework.common.configuration.ComponentBuilder)}.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
@FunctionalInterface
public interface CommandHandlingExceptionHandler extends MessageHandlingExceptionHandler<CommandMessage> {

    /**
     * Called when a command handler throws an exception.
     *
     * @param command the command being handled when the exception occurred
     * @param context the active processing context
     * @param error   the exception thrown by the command handler
     * @return {@link MessageStream#empty()} to suppress the error, {@link MessageStream#failed(Throwable)} to
     *         propagate it, or {@link MessageStream#just(org.axonframework.messaging.core.Message)} to substitute a
     *         result
     */
    @Override
    MessageStream.Single<CommandResultMessage> handle(CommandMessage command,
                                                      ProcessingContext context,
                                                      Throwable error);
}
