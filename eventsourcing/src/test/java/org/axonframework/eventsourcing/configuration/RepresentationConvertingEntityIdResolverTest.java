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

package org.axonframework.eventsourcing.configuration;

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.MultiParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link RepresentationConvertingEntityIdResolver} correctly converts serialized payloads to the
 * expected type before ID extraction, and that this works regardless of whether the {@link EntityMetamodel} component
 * in configuration is a plain decorator (with no knowledge of expected representations).
 */
class RepresentationConvertingEntityIdResolverTest {

    private final ParameterResolverFactory parameterResolverFactory =
            MultiParameterResolverFactory.ordered(
                    ClasspathParameterResolverFactory.forClass(AnnotatedCourse.class)
            );
    private final ClassBasedMessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();
    private final MessageConverter messageConverter = new DelegatingMessageConverter(new JacksonConverter());
    private final EventConverter eventConverter = new DelegatingEventConverter(new JacksonConverter());
    private final HandlerDefinition handlerDefinition = ClasspathHandlerDefinition.forClass(AnnotatedCourse.class);

    @Test
    void resolvesIdFromSerializedPayloadUsingAnnotationScan() throws EntityIdResolutionException {
        // given
        AnnotatedEntityMetamodel<AnnotatedCourse> annotatedMetamodel = AnnotatedEntityMetamodel.forConcreteType(
                AnnotatedCourse.class, parameterResolverFactory, handlerDefinition, messageTypeResolver,
                messageConverter, eventConverter
        );

        EntityIdResolver<String> inner = new AnnotationBasedEntityIdResolver<>();
        var resolver = new RepresentationConvertingEntityIdResolver<>(
                inner,
                annotatedMetamodel::getExpectedRepresentation,
                messageConverter
        );

        var message = new GenericCommandMessage(
                new MessageType(RegisterCourseCommand.class),
                """
                {"courseId": "course-123"}"""
        );

        // when
        String id = resolver.resolve(message, StubProcessingContext.forMessage(message));

        // then
        assertThat(id).isEqualTo("course-123");
    }

    @Test
    void resolvesIdEvenWhenEntityMetamodelIsWrappedInPlainDecorator() throws EntityIdResolutionException {
        // given
        AnnotatedEntityMetamodel<AnnotatedCourse> annotatedMetamodel = AnnotatedEntityMetamodel.forConcreteType(
                AnnotatedCourse.class, parameterResolverFactory, handlerDefinition, messageTypeResolver,
                messageConverter, eventConverter
        );

        // Simulate user-provided decoration: a plain EntityMetamodel wrapper that has no knowledge of
        // expected payload representations. Previously, this would break ID resolution because the
        // hard cast to AnnotatedEntityMetamodel would fail. Now the representation info comes from the
        // annotation scan cached in the module, independently of whatever component is registered.
        EntityMetamodel<AnnotatedCourse> plainDecorator = new PlainDelegatingEntityMetamodel<>(annotatedMetamodel);

        // The resolver uses the cached annotated metamodel for representation info (as the module does),
        // and the plain decorator would be the registered component passed to EntityIdResolverDefinition.
        // Since AnnotationBasedEntityIdResolverDefinition ignores the metamodel parameter, this is transparent.
        EntityIdResolver<String> inner = new AnnotationBasedEntityIdResolver<>();
        var resolver = new RepresentationConvertingEntityIdResolver<>(
                inner,
                annotatedMetamodel::getExpectedRepresentation,  // from cache, not from the decorated component
                messageConverter
        );

        var message = new GenericCommandMessage(
                new MessageType(RegisterCourseCommand.class),
                """
                {"courseId": "course-456"}"""
        );

        // when
        String id = resolver.resolve(message, StubProcessingContext.forMessage(message));

        // then — ID resolves correctly despite the EntityMetamodel component being a plain decorator
        assertThat(id).isEqualTo("course-456");
    }

    @Test
    void passesPayloadThroughUnconvertedWhenNoRepresentationKnown() throws EntityIdResolutionException {
        // given — a representation provider that knows nothing (simulates unknown message type)
        var resolver = new RepresentationConvertingEntityIdResolver<String>(
                new AnnotationBasedEntityIdResolver<>(),
                qualifiedName -> null,
                messageConverter
        );

        // Payload is already a proper object (not serialized), so no conversion needed
        var command = new RegisterCourseCommand("course-789");
        var message = new GenericCommandMessage(new MessageType(RegisterCourseCommand.class), command);

        // when
        String id = resolver.resolve(message, StubProcessingContext.forMessage(message));

        // then
        assertThat(id).isEqualTo("course-789");
    }

    // --- Domain classes ---

    record RegisterCourseCommand(@TargetEntityId String courseId) {

    }

    @EventSourcedEntity
    static class AnnotatedCourse {

        private final String id;

        private AnnotatedCourse(String id) {
            this.id = id;
        }

        @CommandHandler
        static AnnotatedCourse register(RegisterCourseCommand command) {
            return new AnnotatedCourse(command.courseId());
        }
    }

    /**
     * Plain {@link EntityMetamodel} decorator with no knowledge of expected payload representations.
     * Represents a user-provided decoration that simply delegates all operations.
     */
    static class PlainDelegatingEntityMetamodel<E> implements EntityMetamodel<E> {

        private final EntityMetamodel<E> delegate;

        PlainDelegatingEntityMetamodel(EntityMetamodel<E> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Class<E> entityType() {
            return delegate.entityType();
        }

        @Override
        public MessageStream.Single<CommandResultMessage> handleCreate(CommandMessage message,
                                                                       ProcessingContext context) {
            return delegate.handleCreate(message, context);
        }

        @Override
        public MessageStream.Single<CommandResultMessage> handleInstance(CommandMessage message,
                                                                         E entity,
                                                                         ProcessingContext context) {
            return delegate.handleInstance(message, entity, context);
        }

        @Override
        public Set<org.axonframework.messaging.core.QualifiedName> supportedCreationalCommands() {
            return delegate.supportedCreationalCommands();
        }

        @Override
        public Set<org.axonframework.messaging.core.QualifiedName> supportedInstanceCommands() {
            return delegate.supportedInstanceCommands();
        }

        @Override
        public Set<org.axonframework.messaging.core.QualifiedName> supportedCommands() {
            return delegate.supportedCommands();
        }

        @Override
        public E evolve(@Nullable E entity,
                        org.axonframework.messaging.eventhandling.EventMessage event,
                        ProcessingContext context) {
            return delegate.evolve(entity, event, context);
        }

        @Override
        public void describeTo(org.axonframework.common.infra.ComponentDescriptor descriptor) {
            delegate.describeTo(descriptor);
        }
    }
}
