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

package org.axonframework.deadline.annotation;

import org.axonframework.common.ObjectUtils;
import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.deadline.GenericDeadlineMessage;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMemberDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link DeadlineMethodMessageHandlerDefinition}, in particular that it wraps only
 * {@link DeadlineHandler @DeadlineHandler} methods, that the wrapped member only handles {@link DeadlineMessage}s
 * matching its configured deadline name, and that a name-specific handler outranks a wildcard one.
 */
class DeadlineMethodMessageHandlerDefinitionTest {

    private final AnnotatedMessageHandlingMemberDefinition handlerDefinition =
            new AnnotatedMessageHandlingMemberDefinition();
    private final ParameterResolverFactory parameterResolver =
            ClasspathParameterResolverFactory.forClass(getClass());
    private final DeadlineMethodMessageHandlerDefinition testSubject = new DeadlineMethodMessageHandlerDefinition();

    @Nested
    class WrapHandler {

        @Test
        void wrapsAMethodAnnotatedWithDeadlineHandler() throws NoSuchMethodException {
            MessageHandlingMember<Listener> handler = createHandler(Listener.class, "handleAnyDeadline", String.class);

            MessageHandlingMember<Listener> wrapped = testSubject.wrapHandler(handler);

            assertThat(wrapped).isNotSameAs(handler);
            assertThat(wrapped).isInstanceOf(DeadlineHandlingMember.class);
        }

        @Test
        void leavesAPlainEventHandlerMethodUnwrapped() throws NoSuchMethodException {
            MessageHandlingMember<Listener> handler = createHandler(Listener.class, "handleEvent", String.class);

            MessageHandlingMember<Listener> wrapped = testSubject.wrapHandler(handler);

            assertThat(wrapped).isSameAs(handler);
        }
    }

    @Nested
    class CanHandle {

        @Test
        void aWildcardDeadlineHandlerAcceptsAnyDeadlineName() throws NoSuchMethodException {
            MessageHandlingMember<Listener> wrapped =
                    testSubject.wrapHandler(createHandler(Listener.class, "handleAnyDeadline", String.class));

            DeadlineMessage anyDeadline = new GenericDeadlineMessage("someDeadline", new MessageType("deadline"), "x");

            assertThat(wrapped.canHandle(anyDeadline, StubProcessingContext.forMessage(anyDeadline))).isTrue();
        }

        @Test
        void aNamedDeadlineHandlerOnlyAcceptsItsOwnDeadlineName() throws NoSuchMethodException {
            MessageHandlingMember<Listener> wrapped =
                    testSubject.wrapHandler(createHandler(Listener.class, "handleSpecificDeadline", String.class));

            DeadlineMessage matching = new GenericDeadlineMessage("specificDeadline", new MessageType("deadline"), "x");
            DeadlineMessage other = new GenericDeadlineMessage("someOtherDeadline", new MessageType("deadline"), "x");

            assertThat(wrapped.canHandle(matching, StubProcessingContext.forMessage(matching))).isTrue();
            assertThat(wrapped.canHandle(other, StubProcessingContext.forMessage(other))).isFalse();
        }

        @Test
        void aDeadlineHandlerNeverAcceptsAPlainEventMessage() throws NoSuchMethodException {
            MessageHandlingMember<Listener> wrapped =
                    testSubject.wrapHandler(createHandler(Listener.class, "handleAnyDeadline", String.class));

            GenericMessage notADeadline = new GenericMessage(new MessageType("event"), "x");

            assertThat(wrapped.canHandle(notADeadline, StubProcessingContext.forMessage(notADeadline))).isFalse();
        }
    }

    @Nested
    class Priority {

        @Test
        void aNamedDeadlineHandlerOutranksAWildcardOne() throws NoSuchMethodException {
            MessageHandlingMember<Listener> wildcard =
                    testSubject.wrapHandler(createHandler(Listener.class, "handleAnyDeadline", String.class));
            MessageHandlingMember<Listener> named =
                    testSubject.wrapHandler(createHandler(Listener.class, "handleSpecificDeadline", String.class));

            assertThat(named.priority()).isGreaterThan(wildcard.priority());
        }
    }

    private static MessageStream<?> returnTypeConverter(Object result) {
        return MessageStream.just(new GenericMessage(new MessageType(ObjectUtils.nullSafeTypeOf(result)), result));
    }

    private <T> MessageHandlingMember<T> createHandler(Class<T> targetClass,
                                                        String methodName,
                                                        Class<?>... parameterTypes) throws NoSuchMethodException {
        return handlerDefinition.createHandler(
                                        targetClass,
                                        targetClass.getDeclaredMethod(methodName, parameterTypes),
                                        parameterResolver,
                                        DeadlineMethodMessageHandlerDefinitionTest::returnTypeConverter
                                )
                                .orElseThrow(() -> new IllegalArgumentException("Handler creation failed"));
    }

    @SuppressWarnings("unused")
    private static class Listener {

        @EventHandler
        public void handleEvent(String event) {
        }

        @DeadlineHandler
        public void handleAnyDeadline(String event) {
        }

        @DeadlineHandler(deadlineName = "specificDeadline")
        public void handleSpecificDeadline(String event) {
        }
    }
}
