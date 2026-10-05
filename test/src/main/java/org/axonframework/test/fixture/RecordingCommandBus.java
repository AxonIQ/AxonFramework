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

package org.axonframework.test.fixture;

import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * An CommandBus implementation recording all the commands that are dispatched. The recorded commands can then be used
 * to assert expectations with test cases.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @since 5.0.0
 */
@Internal
public class RecordingCommandBus implements CommandBus {

    private final CommandBus delegate;

    /**
     * Recorded commands, mapped to their result. A {@link LinkedHashMap} because assertions compare dispatched
     * commands positionally, and a synchronized one because a streaming event processor dispatches from several
     * worker threads at once. Every iteration of this map synchronizes on it, as
     * {@link Collections#synchronizedMap(Map)} requires.
     */
    private final Map<CommandMessage, Message> recorded = Collections.synchronizedMap(new LinkedHashMap<>());

    /**
     * Creates a new {@code RecordingCommandBus} that will record all commands dispatched to the given
     * {@code delegate}.
     *
     * @param delegate The {@link CommandBus} to which commands will be dispatched.
     */
    public RecordingCommandBus(CommandBus delegate) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate CommandBus may not be null");
    }

    @Override
    public CompletableFuture<CommandResultMessage> dispatch(CommandMessage command,
                                                            @Nullable ProcessingContext processingContext) {
        recorded.put(command, null);
        var commandResult = delegate.dispatch(command, processingContext);
        return commandResult.whenComplete((result, exception) -> {
            if (exception == null) {
                recorded.put(command, result);
            }
        });
    }

    @Override
    public CommandBus subscribe(QualifiedName name, CommandHandler commandHandler) {
        return delegate.subscribe(name, commandHandler);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
    }

    /**
     * Returns map of all the {@link CommandMessage CommandMessages} dispatched, and their corresponding results, in
     * dispatch order.
     * <p>
     * A command that has been dispatched but has not completed yet, or whose dispatch failed, maps to {@code null}.
     *
     * @return A map of all the {@link CommandMessage CommandMessages} dispatched, and their corresponding results.
     */
    public Map<CommandMessage, Message> recorded() {
        synchronized (recorded) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(recorded));
        }
    }

    /**
     * Returns the commands that have been dispatched to this {@link CommandBus}, in dispatch order.
     *
     * @return The commands that have been dispatched to this {@link CommandBus}
     */
    public List<CommandMessage> recordedCommands() {
        synchronized (recorded) {
            return Collections.unmodifiableList(new ArrayList<>(recorded.keySet()));
        }
    }

    /**
     * Returns the result of the given {@code command}.
     *
     * @param command The command for which the result is returned.
     * @return The result of the given {@code command}. May be {@code null} if the command has not been dispatched, is
     * still being handled, or completed exceptionally.
     */
    @Nullable
    public Message resultOf(CommandMessage command) {
        Objects.requireNonNull(command, "Command Message may not be null.");
        return recorded.get(command);
    }

    /**
     * Resets this recording {@link CommandBus}, by removing all recorded {@link CommandMessage CommandMessages}.
     *
     * @return This recording {@link CommandBus}, for fluent interfacing.
     */
    public RecordingCommandBus reset() {
        recorded.clear();
        return this;
    }
}
