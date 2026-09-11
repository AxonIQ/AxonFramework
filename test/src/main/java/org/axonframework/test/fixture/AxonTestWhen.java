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

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventSink;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Implementation of the {@link AxonTestPhase.When when-phase} of the {@link AxonTestFixture}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class AxonTestWhen implements AxonTestPhase.When {

    private final AxonConfiguration configuration;
    private final AxonTestFixture.Customization customization;
    private final CommandBus commandBus;
    private final EventSink eventSink;
    private final RecordingComponentsRegistry recordings;
    private final MessageTypeResolver messageTypeResolver;
    private final UnitOfWorkFactory unitOfWorkFactory;
    private final Set<String> inputMessageIdentifiers = new HashSet<>();

    private Message actualResult;
    private Throwable actualException;

    /**
     * Constructs an {@code AxonTestWhen} for the given parameters.
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
    public AxonTestWhen(
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
        recordings.commandBus().reset();
        recordings.eventSink().reset();
        this.messageTypeResolver = messageTypeResolver;
        this.unitOfWorkFactory = unitOfWorkFactory;
    }

    @Override
    public Command command(Object payload, Metadata metadata) {
        CommandMessage message;
        if (payload instanceof CommandMessage commandMessage) {
            message = commandMessage.andMetadata(metadata);
        } else {
            var messageType = messageTypeResolver.resolveOrThrow(payload);
            message = new GenericCommandMessage(messageType, payload, metadata);
        }
        inputMessageIdentifiers.add(message.identifier());
        inUnitOfWorkOnInvocation(processingContext ->
                                         commandBus.dispatch(message, processingContext)
                                                   .whenComplete((r, e) -> {
                                                       if (e == null) {
                                                           actualResult = r;
                                                           actualException = null;
                                                       } else {
                                                           actualResult = null;
                                                           actualException = FutureUtils.unwrap(e);
                                                       }
                                                   })
        );
        return new Command();
    }

    @Override
    public Event event(Object payload, Metadata metadata) {
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
    public Event events(List<?> events) {
        var messages = events.stream()
                             .map(e -> e instanceof EventMessage message
                                     ? message
                                     : toGenericEventMessage(e, Metadata.emptyInstance())
                             ).toArray(EventMessage[]::new);
        return events(messages);
    }

    @Override
    public Event events(EventMessage... messages) {
        for (EventMessage message : messages) {
            inputMessageIdentifiers.add(message.identifier());
        }
        inUnitOfWorkOnInvocation(processingContext -> eventSink.publish(processingContext, messages));
        return new Event();
    }

    private Predicate<Message> whenPhaseOutputFilter() {
        Set<String> identifiers = Set.copyOf(inputMessageIdentifiers);
        return message -> !identifiers.contains(message.identifier());
    }

    private void inUnitOfWorkOnInvocation(Function<ProcessingContext, CompletableFuture<?>> action) {
        var unitOfWork = unitOfWorkFactory.create();
        unitOfWork.onInvocation(action);
        awaitCompletion(unitOfWork.execute());
    }

    private void awaitCompletion(CompletableFuture<?> completion) {
        try {
            completion.join();
        } catch (Exception e) {
            this.actualResult = null;
            this.actualException = FutureUtils.unwrap(e);
        }
    }

    class Command implements AxonTestPhase.When.Command {

        @Override
        public AxonTestPhase.Then.Command then() {
            return new AxonTestThenCommand(
                    configuration,
                    customization,
                    recordings,
                    whenPhaseOutputFilter(),
                    actualResult,
                    actualException
            );
        }
    }

    class Event implements AxonTestPhase.When.Event {

        @Override
        public AxonTestPhase.Then.Event then() {
            return new AxonTestThenEvent(
                    configuration,
                    customization,
                    recordings,
                    whenPhaseOutputFilter(),
                    actualException
            );
        }
    }

    @Override
    public AxonTestPhase.When.Nothing nothing() {
        return new Nothing();
    }

    class Nothing implements AxonTestPhase.When.Nothing {

        @Override
        public AxonTestPhase.Then.Nothing then() {
            return new AxonTestThenNothing(
                    configuration,
                    customization,
                    recordings,
                    whenPhaseOutputFilter(),
                    actualException
            );
        }
    }
}
