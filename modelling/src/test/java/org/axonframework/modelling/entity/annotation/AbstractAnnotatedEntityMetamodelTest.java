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

package org.axonframework.modelling.entity.annotation;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.MultiParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.SimpleResourceParameterResolverFactory;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.jspecify.annotations.NonNull;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Abstract base class for tests of the {@link AnnotatedEntityMetamodel} that provide common setup for parameter
 * resolver factory and message type resolver. In addition, it makes it easier to fire commands and events against the
 * metamodel.
 * <p>
 * This class evolves the entity based on any events published, mimicking the behavior of a repository.
 *
 * @param <E> The type of the entity being tested.
 * @author Mitchell Herrijgers
 */
public abstract class AbstractAnnotatedEntityMetamodelTest<E> {

    protected final ParameterResolverFactory parameterResolverFactory = createParameterResolverFactory();
    protected final HandlerDefinition handlerDefinition = ClasspathHandlerDefinition.forClass(getClass());
    protected final MessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();
    protected final MessageConverter messageConverter = new DelegatingMessageConverter(new JacksonConverter());
    protected final EventConverter eventConverter = new DelegatingEventConverter(new JacksonConverter());
    protected final AnnotatedEntityMetamodel<E> metamodel = getMetamodel();
    protected E entityState = null;
    protected List<Object> publishedEvents = new LinkedList<>();

    protected abstract AnnotatedEntityMetamodel<E> getMetamodel();

    protected Object dispatchInstanceCommand(Object command) {
        CommandMessage message = createCommand(command);
        try {
            return metamodel.handleInstance(message, entityState, StubProcessingContext.forMessage(message))
                            .first()
                            .asCompletableFuture()
                            .thenApply(result -> {
                                if (result != null) {
                                    return result.message();
                                } else {
                                    return new GenericCommandResultMessage(new MessageType(Void.class), (Object) null);
                                }
                            })
                            .thenApply(CommandResultMessage::payload)
                            .join();
        } catch (Exception e) {
            if (e instanceof CompletionException && e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw e;
        }
    }

    protected Object dispatchCreateCommand(Object command) {
        CommandMessage message = createCommand(command);
        try {
            return metamodel.handleCreate(message, StubProcessingContext.forMessage(message))
                            .first()
                            .asCompletableFuture()
                            .thenApply(MessageStream.Entry::message)
                            .thenApply(CommandResultMessage::payload)
                            .join();
        } catch (Exception e) {
            if (e instanceof CompletionException && e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw e;
        }
    }

    protected <P> CommandMessage createCommand(P command) {
        return new GenericCommandMessage(new MessageType(command.getClass()), command);
    }

    protected <P> EventMessage createEvent(P event) {
        return new GenericEventMessage(new MessageType(event.getClass()), event);
    }

    protected ParameterResolverFactory createParameterResolverFactory() {
        var appender = new EntityEvolvingEventAppender();
        return new MultiParameterResolverFactory(
                ClasspathParameterResolverFactory.forClass(
                        getClass()),
                new SimpleResourceParameterResolverFactory(Set.of(appender))
        );
    }

    protected QualifiedName qualifiedName(Class<?> clazz) {
        return messageTypeResolver.resolveOrThrow(clazz).qualifiedName();
    }

    private class EntityEvolvingEventAppender implements EventAppender {

        @Override
        public void append(@NonNull List<?> events) {
            publishedEvents.addAll(events);
            if (entityState == null) {
                return;
            }
            events.forEach(event -> {
                EventMessage eventMessage;
                if (event instanceof EventMessage) {
                    eventMessage = (EventMessage) event;
                } else {
                    eventMessage = createEvent(event);
                }
                metamodel.evolve(entityState, eventMessage, StubProcessingContext.forMessage(eventMessage));
            });
        }

        @Override
        public void append(@NonNull List<?> events, @NonNull Metadata metadata) {
            publishedEvents.addAll(events);
            if (entityState == null) {
                return;
            }
            events.forEach(event -> {
                EventMessage eventMessage;
                if (event instanceof EventMessage e) {
                    eventMessage = e.andMetadata(metadata);
                } else {
                    eventMessage = new GenericEventMessage(
                            new MessageType(event.getClass()), event, metadata
                    );
                }
                metamodel.evolve(entityState, eventMessage, StubProcessingContext.forMessage(eventMessage));
            });
        }

        @Override
        public void describeTo(@NonNull ComponentDescriptor descriptor) {
            throw new UnsupportedOperationException("Not required for testing");
        }
    }
}
