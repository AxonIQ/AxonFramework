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

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventSink;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Implementation of the {@link AxonTestPhase.Given given-phase} of the {@link AxonTestFixture}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class AxonTestGiven implements AxonTestPhase.Given {

    private final AxonConfiguration configuration;
    private final AxonTestFixture.Customization customization;
    private final CommandBus commandBus;
    private final EventSink eventSink;
    private final RecordingComponentsRegistry recordings;
    private final MessageTypeResolver messageTypeResolver;
    private final UnitOfWorkFactory unitOfWorkFactory;

    /**
     * Constructs a {@code AxonTestGiven} for the given parameters.
     *
     * @param configuration       The configuration which this test fixture phase is based on.
     * @param customization       Collection of customizations made for this test fixture.
     * @param commandBus          The outermost {@link CommandBus}, used to dispatch commands through the full
     *                            decorator chain (including interceptors).
     * @param eventSink           The outermost {@link EventSink}, used to publish events through the full
     *                            decorator chain (including interceptors).
     * @param recordings          The registry holding recording components for assertions.
     * @param messageTypeResolver The message type resolver used to generate the
     *                            {@link MessageType} out of command, event, or query
     *                            payloads provided to this phase.
     * @param unitOfWorkFactory   The factory of the {@link UnitOfWork}, used to
     *                            execute every test in.
     */
    AxonTestGiven(
            AxonConfiguration configuration,
            AxonTestFixture.Customization customization,
            CommandBus commandBus,
            EventSink eventSink,
            RecordingComponentsRegistry recordings,
            MessageTypeResolver messageTypeResolver,
            UnitOfWorkFactory unitOfWorkFactory
    ) {
        this.configuration = configuration;
        this.customization = customization;
        this.commandBus = commandBus;
        this.eventSink = eventSink;
        this.recordings = recordings;
        this.messageTypeResolver = messageTypeResolver;
        this.unitOfWorkFactory = unitOfWorkFactory;
    }

    @Override
    public AxonTestPhase.Given noPriorActivity() {
        return this;
    }

    @Override
    public AxonTestPhase.Given event(Object payload, Metadata metadata) {
        if (payload instanceof EventMessage message) {
            return events(message.andMetadata(metadata));
        }
        var eventMessage = toGenericEventMessage(payload, metadata);
        return events(eventMessage);
    }

    private GenericEventMessage toGenericEventMessage(Object payload, Metadata metadata) {
        var messageType = messageTypeResolver.resolveOrThrow(payload);
        return new GenericEventMessage(
                messageType,
                payload,
                metadata
        );
    }

    @Override
    public AxonTestPhase.Given events(List<?> events) {
        var messages = events.stream()
                             .map(e -> e instanceof EventMessage message
                                     ? message
                                     : toGenericEventMessage(e, Metadata.emptyInstance())
                             ).toArray(EventMessage[]::new);
        return events(messages);
    }

    @Override
    public AxonTestPhase.Given events(EventMessage... messages) {
        inUnitOfWorkOnInvocation(
                processingContext -> eventSink.publish(processingContext, messages)
        );
        return this;
    }

    private void inUnitOfWorkOnInvocation(Function<ProcessingContext, CompletableFuture<?>> action) {
        var unitOfWork = unitOfWorkFactory.create();
        unitOfWork.onInvocation(action);
        unitOfWork.execute().join();
    }

    @Override
    public AxonTestPhase.Given command(Object payload, Metadata metadata) {
        if (payload instanceof CommandMessage message) {
            return commands(message.andMetadata(metadata));
        }
        var commandMessage = toGenericCommandMessage(payload, metadata);
        return commands(commandMessage);
    }

    @Override
    public AxonTestPhase.Given commands(List<?> commands) {
        var messages = commands.stream()
                               .map(c -> c instanceof CommandMessage message
                                       ? message
                                       : toGenericCommandMessage(c, Metadata.emptyInstance())
                               ).toArray(CommandMessage[]::new);
        return commands(messages);
    }

    @Override
    public AxonTestPhase.Given executeAsync(Function<Configuration, CompletableFuture<?>> function) {
        function.apply(configuration).join();
        return this;
    }

    private GenericCommandMessage toGenericCommandMessage(Object payload,
                                                          Metadata metadata) {
        var messageType = messageTypeResolver.resolveOrThrow(payload);
        return new GenericCommandMessage(
                messageType,
                payload,
                metadata
        );
    }

    @Override
    public AxonTestPhase.Given commands(CommandMessage... messages) {
        for (var message : messages) {
            inUnitOfWorkOnInvocation(processingContext -> commandBus.dispatch(message, processingContext));
        }
        return this;
    }

    @Override
    public AxonTestPhase.When when() {
        return new AxonTestWhen(
                configuration,
                customization,
                commandBus,
                eventSink,
                recordings,
                messageTypeResolver,
                unitOfWorkFactory
        );
    }

    @Override
    public AxonTestPhase.Then.Nothing then() {
        return new AxonTestThenNothing(
                configuration,
                customization,
                recordings,
                message -> true,
                null
        );
    }
}
