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

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.EnhancingHandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.tracing.annotation.TracingHandlerEnhancerDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that handler members wrapped by the tracing enhancer keep their handler classification when an entity
 * metamodel is built: traced event(-sourcing) handlers must not surface as command handlers.
 * <p>
 * Regression coverage: an earlier tracing wrapper implemented the command, event, and query marker interfaces at the
 * same time, so entity metamodel construction (which classifies by marker interface) also interpreted traced event
 * handlers as command handlers. Entities evolving the same event then attempted duplicate command subscriptions during
 * application startup.
 */
class TracedEntityHandlerClassificationTest {

    private static <E> AnnotatedEntityMetamodel<E> tracedMetamodel(Class<E> entityType) {
        HandlerDefinition tracedDefinition = new EnhancingHandlerDefinition(
                ClasspathHandlerDefinition.forClass(entityType),
                new TracingHandlerEnhancerDefinition()
        );
        return AnnotatedEntityMetamodel.forConcreteType(
                entityType,
                ClasspathParameterResolverFactory.forClass(entityType),
                tracedDefinition,
                new ClassBasedMessageTypeResolver(),
                new DelegatingMessageConverter(new JacksonConverter()),
                new DelegatingEventConverter(new JacksonConverter())
        );
    }

    @Test
    void tracedEventHandlersAreNotClassifiedAsCommandHandlers() {
        // given / when an entity with one command handler and one event handler, built with tracing enhancement
        AnnotatedEntityMetamodel<GuestBook> metamodel = tracedMetamodel(GuestBook.class);

        // then only the command surfaces as a command; the traced event handler keeps its event classification
        assertThat(metamodel.supportedCommands()).containsExactly(new QualifiedName(SignGuestBook.class));
        assertThat(metamodel.getExpectedRepresentation(new QualifiedName(GuestBookSigned.class)))
                .isEqualTo(GuestBookSigned.class);
    }

    @Test
    void buildingATracedMetamodelDoesNotThrow() {
        // given / when / then no duplicate command subscription is attempted during metamodel construction
        assertThatCode(() -> tracedMetamodel(GuestBook.class)).doesNotThrowAnyException();
    }

    private record SignGuestBook(String guest) {

    }

    private record GuestBookSigned(String guest) {

    }

    @SuppressWarnings("unused")
    private static final class GuestBook {

        @CommandHandler
        public void handle(SignGuestBook command) {
            // handled through the metamodel
        }

        @EventHandler
        public void on(GuestBookSigned event) {
            // evolved through the metamodel
        }
    }
}
