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

package org.axonframework.messaging.commandhandling.gateway;

import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandDispatcherParameterResolverFactory;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.annotation.MessageHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.concurrent.CompletableFuture;

/**
 * Component that dispatches commands to a {@link CommandGateway} in a predefined
 * {@link ProcessingContext context}. This makes the {@code CommandDispatcher} the <b>preferred</b> way to send commands from within another message handling method.
 * <p>
 * The commands will be dispatched in the context this dispatcher was created for. You can construct one through the
 * {@link #forContext(ProcessingContext)}.
 * <p>
 * When using annotation-based {@link MessageHandler @MessageHandler-methods} and
 * you have declared an argument of type {@link CommandDispatcher}, the dispatcher will automatically be injected by the
 * {@link CommandDispatcherParameterResolverFactory}.
 * <p>
 * As this component is {@link ProcessingContext}-scoped, it is not retrievable from the {@link Configuration}.
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface CommandDispatcher extends DescribableComponent {

    /**
     * Creates a dispatcher for the given {@link ProcessingContext}.
     * <p>
     * You can use this dispatcher <b>only</b> for the context it was created for. Every invocation returns a fresh
     * instance bound to the given {@code context} - it is never cached or shared with another call, even for the
     * same {@code context}. This matters when {@code context} overrides one or more resources on top of a shared
     * parent (e.g. one such override per event in a streaming processor's batch): each context with its own
     * overridden resources must resolve its own dispatcher rather than risk one context's dispatcher leaking into
     * another's.
     *
     * @param context The {@link ProcessingContext} to create the dispatcher for.
     * @return A fresh command dispatcher specific for the given {@code context}.
     */
    static CommandDispatcher forContext(ProcessingContext context) {
        return new ContextAwareCommandDispatcher(context.component(CommandGateway.class), context);
    }

    /**
     * Sends the given {@code command} and returns a {@link CompletableFuture} immediately, without waiting for the
     * command to execute.
     * <p>
     * The caller will therefore not receive any immediate feedback on the {@code command's} execution. Instead, hooks
     * <em>can</em> be added to the returned {@code CompletableFuture} to react on success or failure of command
     * execution.
     * <p>
     * Note that this operation expects the {@link CommandBus} to use new threads for
     * command execution.
     * <p/>
     * The given {@code command} is wrapped as the payload of the
     * {@link CommandMessage} that is eventually posted on the {@code CommandBus},
     * unless the {@code command} already implements {@link Message}. In that case, a
     * {@code CommandMessage} is constructed from that message's payload and
     * {@link org.axonframework.messaging.core.Metadata}.
     *
     * @param command      The command payload or {@link CommandMessage} to send.
     * @param expectedType The expected result type.
     * @param <R>          The generic type of the expected response.
     * @return A {@link CompletableFuture} that will be resolved successfully or exceptionally based on the eventual
     * command execution result.
     */
    default <R> CompletableFuture<R> send(Object command,
                                          Class<R> expectedType) {
        return send(command).resultAs(expectedType);
    }

    /**
     * Sends the given {@code command} and returns a {@link CommandResult} immediately, without waiting for the command
     * to execute.
     * <p>
     * The caller will therefore not receive any immediate feedback on the {@code command's} execution. Instead, hooks
     * <em>can</em> be added to the returned {@code CommandResult} to react on success or failure of command
     * execution. A shorthand to retrieve a {@link CompletableFuture} is available through the
     * {@link CommandResult#getResultMessage()} operation.
     * <p>
     * Note that this operation expects the {@link CommandBus} to use new threads for
     * command execution.
     * <p/>
     * The given {@code command} is wrapped as the payload of the
     * {@link CommandMessage} that is eventually posted on the {@code CommandBus},
     * unless the {@code command} already implements {@link Message}. In that case, a
     * {@code CommandMessage} is constructed from that message's payload and
     * {@link org.axonframework.messaging.core.Metadata}.
     *
     * @param command The command payload or {@link CommandMessage} to send.
     * @return A command result success and failure hooks can be registered. The
     * {@link CommandResult#getResultMessage()} serves as a shorthand to retrieve the response.
     */
    CommandResult send(Object command);

    /**
     * Sends the given {@code command} with the given {@code metadata} and returns a {@link CommandResult} immediately,
     * without waiting for the command to execute.
     * <p>
     * The caller will therefore not receive any immediate feedback on the {@code command's} execution. Instead, hooks
     * <em>can</em> be added to the returned {@code CommandResult} to react on success or failure of command
     * execution. A shorthand to retrieve a {@link CompletableFuture} is available through the
     * {@link CommandResult#getResultMessage()} operation.
     * <p>
     * Note that this operation expects the {@link CommandBus} to use new threads for
     * command execution.
     * <p/>
     * The given {@code command} and {@code metadata} are wrapped as the payload of the
     * {@link CommandMessage} that is eventually posted on the
     * {@link CommandBus}, unless the {@code command} already implements
     * {@link Message}. In that case, a {@code CommandMessage} is constructed from that
     * message's payload and {@link Metadata}. The provided {@code metadata} is attached
     * afterward in this case.
     *
     * @param command  The command payload or {@link CommandMessage} to send.
     * @param metadata Metadata that must be registered with the {@code command}.
     * @return A command result success and failure hooks can be registered. The
     * {@link CommandResult#getResultMessage()} serves as a shorthand to retrieve the response.
     */
    CommandResult send(Object command,
                       Metadata metadata);
}
