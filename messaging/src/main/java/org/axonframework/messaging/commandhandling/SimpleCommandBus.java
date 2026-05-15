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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Implementation of the {@code CommandBus} that {@link #dispatch(CommandMessage, ProcessingContext) dispatches}
 * commands to the handlers subscribed to that specific command's {@link QualifiedName name}. Furthermore, it is in
 * charge of invoking the {@link #subscribe(QualifiedName, CommandHandler) subscribed}
 * {@link CommandHandler command handlers} when a command is being dispatched.
 *
 * @author Allard Buijze
 * @author Martin Tilma
 * @since 0.5.0
 */
public class SimpleCommandBus implements CommandBus {

    private static final Logger logger = LoggerFactory.getLogger(SimpleCommandBus.class);

    private final ConcurrentMap<QualifiedName, CommandHandler> subscriptions = new ConcurrentHashMap<>();
    private final UnitOfWorkFactory unitOfWorkFactory;

    /**
     * Construct a {@code SimpleCommandBus}, using the given {@code unitOfWorkFactory} to construct
     * {@link ProcessingContext contexts} to handle commands in.
     *
     * @param unitOfWorkFactory the {@code UnitOfWorkFactory} used to construct {@link ProcessingContext contexts} to
     *                          handle commands in
     */
    public SimpleCommandBus(UnitOfWorkFactory unitOfWorkFactory) {
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "The given UnitOfWorkFactory cannot be null.");
    }

    /**
     * @throws DuplicateCommandHandlerSubscriptionException when a subscription already exists for the given
     *                                                      {@code name}
     */
    @Override
    public SimpleCommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
        CommandHandler handler = requireNonNull(commandHandler, "Given command handler cannot be null.");
        logger.debug("Subscribing command handler with name [{}].", name);
        var existingHandler =
                subscriptions.putIfAbsent(requireNonNull(name, "The command name cannot be null."), handler);

        if (existingHandler != null && existingHandler != handler) {
            throw new DuplicateCommandHandlerSubscriptionException(name, existingHandler, handler);
        }
        return this;
    }

    @Override
    public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                            @Nullable ProcessingContext processingContext) {
        return findCommandHandlerFor(command)
                .map(handler -> handle(command, handler))
                .orElseGet(() -> CompletableFuture.failedFuture(new NoHandlerForCommandException(format(
                        "No handler was subscribed for command [%s].", command.type()
                ))));
    }

    private Optional<CommandHandler> findCommandHandlerFor(CommandMessage command) {
        return Optional.ofNullable(subscriptions.get(command.type().qualifiedName()));
    }

    /**
     * Performs the actual handling logic.
     *
     * @param command the actual command to handle
     * @param handler the handler that must be invoked for this command
     * @return the {@code CompletableFuture} providing the result of the command, once finished
     */
    protected CompletableFuture<CommandResultMessage> handle(CommandMessage command,
                                                             CommandHandler handler) {
        if (logger.isDebugEnabled()) {
            logger.debug("Handling command [{} ({})]", command.identifier(), command.type());
        }
        UnitOfWork unitOfWork = unitOfWorkFactory.create(command.identifier());

        var result = unitOfWork.executeWithResult(c -> handler.handle(command, c).first().asCompletableFuture());
        if (logger.isDebugEnabled()) {
            result = result.whenComplete((r, e) -> {
                if (e == null) {
                    logger.debug("Command [{} ({})] completed successfully",
                                 command.identifier(),
                                 command.type());
                } else {
                    logger.debug("Command [{} ({})] completed exceptionally",
                                 command.identifier(),
                                 command.type(),
                                 e);
                }
            });
        }
        return result.thenApply(e -> e == null ? null : e.message());
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("unitOfWorkFactory", unitOfWorkFactory);
        descriptor.describeProperty("subscriptions", subscriptions);
    }
}
