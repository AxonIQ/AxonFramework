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

package org.axonframework.test.saga;

import org.axonframework.common.ObjectUtils;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.test.util.CallbackBehavior;
import org.axonframework.test.util.DefaultCallbackBehavior;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link CommandBus} a {@link SagaTestFixture} runs on.
 * <p>
 * Axon Framework 4's fixture answered every command from a {@link CallbackBehavior}, defaulting to
 * {@link DefaultCallbackBehavior} and therefore to {@code null}. A command with no handler succeeded with a null
 * result, and Axon Framework 4 saga tests rely on that: a Saga awaiting the result carries on rather than failing.
 * Dispatching on an ordinary command bus instead would fail with no handler found.
 * <p>
 * This keeps that answer while staying useful to a test written the Axon Framework 5 way. A command reaches a handler
 * subscribed through {@link FixtureConfiguration#customize(java.util.function.UnaryOperator) customize}; only a
 * command nobody handles falls back to the {@code CallbackBehavior}. Axon Framework 4 cannot tell the difference,
 * because nothing subscribes a handler in an Axon Framework 4 saga test.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
class FixtureCommandBus implements CommandBus {

    private final CommandBus delegate;
    private final Set<QualifiedName> subscribed = ConcurrentHashMap.newKeySet();

    private volatile CallbackBehavior callbackBehavior = new DefaultCallbackBehavior();

    /**
     * Constructs a {@code FixtureCommandBus} handing commands with a subscribed handler to the given {@code delegate}.
     *
     * @param delegate the command bus invoking the handlers subscribed on this one
     */
    FixtureCommandBus(CommandBus delegate) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate CommandBus may not be null.");
    }

    /**
     * Sets the behaviour answering commands that no subscribed handler takes.
     *
     * @param callbackBehavior the behaviour deciding what such a command returns
     */
    void setCallbackBehavior(CallbackBehavior callbackBehavior) {
        this.callbackBehavior = Objects.requireNonNull(callbackBehavior, "The callbackBehavior may not be null.");
    }

    @Override
    public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                            @Nullable ProcessingContext processingContext) {
        if (subscribed.contains(command.type().qualifiedName())) {
            return delegate.dispatch(command, processingContext);
        }
        try {
            return CompletableFuture.completedFuture(
                    asCommandResultMessage(callbackBehavior.handle(command.payload(), command.metadata()))
            );
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
        subscribed.add(name);
        delegate.subscribe(name, commandHandler);
        return this;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("callbackBehavior", callbackBehavior);
    }

    private static CommandResultMessage asCommandResultMessage(@Nullable Object result) {
        if (result instanceof CommandResultMessage commandResultMessage) {
            return commandResultMessage;
        }
        if (result instanceof Message message) {
            return new GenericCommandResultMessage(message);
        }
        return new GenericCommandResultMessage(new MessageType(ObjectUtils.nullSafeTypeOf(result)), result);
    }
}
