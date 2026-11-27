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

package org.axonframework.messaging.core.annotation;

import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.core.annotation.MessageStreamResolverUtils.resolveToStream;

/**
 * Test class validating the {@link MultiHandlerDefinition}.
 *
 * @author Mateusz Nowak
 */
class MultiHandlerDefinitionTest {

    private static final ParameterResolverFactory PARAMETER_RESOLVER_FACTORY =
            ClasspathParameterResolverFactory.forClass(MultiHandlerDefinitionTest.class);
    private static final Function<Object, MessageStream<?>> MESSAGE_STREAM_RESOLVER =
            result -> resolveToStream(result, new ClassBasedMessageTypeResolver());

    @Nested
    class CreateHandlers {

        @Test
        void returnsTheMembersOfTheFirstDefinitionThatCreatesAny() throws NoSuchMethodException {
            // given
            Method method = handleMethod();
            MessageHandlingMember<TestHandler> stringMember = memberFor(method, String.class);
            MessageHandlingMember<TestHandler> integerMember = memberFor(method, Integer.class);
            StubHandlerDefinition empty = new StubHandlerDefinition(List.of());
            StubHandlerDefinition first = new StubHandlerDefinition(List.of(stringMember, integerMember));
            StubHandlerDefinition later = new StubHandlerDefinition(List.of(memberFor(method, Long.class)));
            RecordingHandlerEnhancerDefinition enhancer = new RecordingHandlerEnhancerDefinition();
            MultiHandlerDefinition testSubject = new MultiHandlerDefinition(List.of(empty, first, later), enhancer);

            // when
            List<MessageHandlingMember<TestHandler>> result = createHandlers(testSubject, method);

            // then
            assertThat(result).hasSize(2);
            assertThat(enhancer.wrapped).containsExactly(stringMember, integerMember);
            assertThat(empty.invocations).isEqualTo(1);
            assertThat(first.invocations).isEqualTo(1);
            // the first definition that creates members decides, so the later definition is never asked
            assertThat(later.invocations).isZero();
        }

        @Test
        void wrapsEveryMemberWithTheEnhancer() throws NoSuchMethodException {
            // given
            Method method = handleMethod();
            MessageHandlingMember<TestHandler> stringMember = memberFor(method, String.class);
            MessageHandlingMember<TestHandler> integerMember = memberFor(method, Integer.class);
            StubHandlerDefinition definition = new StubHandlerDefinition(List.of(stringMember, integerMember));
            MultiHandlerDefinition testSubject =
                    new MultiHandlerDefinition(List.of(definition), new RecordingHandlerEnhancerDefinition());

            // when
            List<MessageHandlingMember<TestHandler>> result = createHandlers(testSubject, method);

            // then
            assertThat(result).allSatisfy(member -> assertThat(member).isInstanceOf(EnhancedMember.class));
            assertThat(result).map(member -> (Object) ((EnhancedMember<?>) member).original)
                              .containsExactly(stringMember, integerMember);
        }

        @Test
        void returnsNoMembersWhenNoDefinitionCreatesAny() throws NoSuchMethodException {
            // given
            Method method = handleMethod();
            RecordingHandlerEnhancerDefinition enhancer = new RecordingHandlerEnhancerDefinition();
            MultiHandlerDefinition testSubject = new MultiHandlerDefinition(
                    List.of(new StubHandlerDefinition(List.of()), new StubHandlerDefinition(List.of())), enhancer
            );

            // when
            List<MessageHandlingMember<TestHandler>> result = createHandlers(testSubject, method);

            // then
            assertThat(result).isEmpty();
            assertThat(enhancer.wrapped).isEmpty();
        }
    }

    private static List<MessageHandlingMember<TestHandler>> createHandlers(MultiHandlerDefinition testSubject,
                                                                          Method method) {
        return testSubject.createHandlers(TestHandler.class, method, PARAMETER_RESOLVER_FACTORY,
                                          MESSAGE_STREAM_RESOLVER);
    }

    private static Method handleMethod() throws NoSuchMethodException {
        return TestHandler.class.getMethod("handle", Object.class);
    }

    private static MessageHandlingMember<TestHandler> memberFor(Method method, Class<?> payloadType) {
        return new MethodInvokingMessageHandlingMember<>(
                method, EventMessage.class, payloadType, PARAMETER_RESOLVER_FACTORY, MESSAGE_STREAM_RESOLVER
        );
    }

    /**
     * A {@link HandlerDefinition} returning a fixed list of members and counting how often it is asked for them.
     */
    private static class StubHandlerDefinition implements HandlerDefinition {

        private final List<MessageHandlingMember<TestHandler>> members;
        private int invocations;

        private StubHandlerDefinition(List<MessageHandlingMember<TestHandler>> members) {
            this.members = members;
        }

        @Override
        public <T> Optional<MessageHandlingMember<T>> createHandler(Class<T> declaringType,
                                                                    Method method,
                                                                    ParameterResolverFactory parameterResolverFactory,
                                                                    Function<Object, MessageStream<?>> messageStreamResolver) {
            return createHandlers(declaringType, method, parameterResolverFactory, messageStreamResolver)
                    .stream()
                    .findFirst();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> List<MessageHandlingMember<T>> createHandlers(Class<T> declaringType,
                                                                 Method method,
                                                                 ParameterResolverFactory parameterResolverFactory,
                                                                 Function<Object, MessageStream<?>> messageStreamResolver) {
            invocations++;
            return (List<MessageHandlingMember<T>>) (List<?>) members;
        }
    }

    /**
     * A {@link HandlerEnhancerDefinition} wrapping every member in an {@link EnhancedMember} and recording the
     * members it wrapped.
     */
    private static class RecordingHandlerEnhancerDefinition implements HandlerEnhancerDefinition {

        private final List<MessageHandlingMember<?>> wrapped = new ArrayList<>();

        @Override
        public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
            wrapped.add(original);
            return new EnhancedMember<>(original);
        }
    }

    private static class EnhancedMember<T> extends WrappedMessageHandlingMember<T> {

        private final MessageHandlingMember<T> original;

        private EnhancedMember(MessageHandlingMember<T> original) {
            super(original);
            this.original = original;
        }
    }

    @SuppressWarnings("unused")
    public static class TestHandler {

        public void handle(Object payload) {
        }
    }
}
