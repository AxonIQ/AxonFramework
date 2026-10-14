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

import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * The mechanism that dispatches {@link CommandMessage commands} to their appropriate
 * {@link CommandHandler command handler}.
 * <p>
 * Command handlers can {@link #subscribe(QualifiedName, CommandHandler) subscribe} to the command bus to handle
 * commands matching the {@link QualifiedName} in the {@link CommandMessage#type() command type}.
 * <p>
 * Hence, commands {@link #dispatch(CommandMessage, ProcessingContext) dispatched} match a command handler based on
 * "command name."
 * <p>
 * Only a <em>single</em> handler may be subscribed for a given command name at any time.
 *
 * @author Allard Buijze
 * @since 0.5
 */
public interface CommandBus extends CommandHandlerRegistry<CommandBus>, DescribableComponent {

    /**
     * Dispatch the given {@code command} to the {@link CommandHandler command handler}
     * {@link #subscribe(QualifiedName, CommandHandler) subscribed} to the given {@code command}'s name. The name is
     * typically deferred from the {@link Message#type()}, which contains a {@link MessageType#qualifiedName()}.
     *
     * @param command           the command to dispatch
     * @param processingContext the processing context under which the command is being published (can be {@code null})
     * @return the {@code CompletableFuture} providing the {@link CommandResultMessage result of the command} once
     * finished. The result message will contain a {@code null} {@link CommandResultMessage#payload()} if the
     * {@link CommandHandler command handler} returned {@code null}
     * @throws NoHandlerForCommandException when no {@link CommandHandler command handler} is registered for the given
     *                                      {@code command}'s name.
     */
    CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                     @Nullable ProcessingContext processingContext);
}
