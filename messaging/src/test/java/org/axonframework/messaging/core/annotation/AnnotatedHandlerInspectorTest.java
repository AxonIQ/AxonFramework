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

import org.axonframework.common.util.MockException;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptor;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.junit.jupiter.api.*;
import org.mockito.internal.util.collections.*;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.core.annotation.MessageStreamResolverUtils.resolveToStream;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests validating the {@link AnnotatedHandlerInspector}.
 *
 * @author Milan Savic
 */
class AnnotatedHandlerInspectorTest {

    private static ParameterResolverFactory parameterResolverFactory;
    private AnnotatedHandlerInspector<A> inspector;

    @BeforeAll
    static void init() {
        parameterResolverFactory = ClasspathParameterResolverFactory.forClass(AnnotatedHandlerInspectorTest.class);
    }

    @BeforeEach
    void setUp() {
        inspector = AnnotatedHandlerInspector.inspectType(A.class,
                                                          parameterResolverFactory,
                                                          ClasspathHandlerDefinition.forClass(A.class),
                                                          new HashSet<>(asList(D.class, C.class)));
    }

    @Test
    void complexHandlerHierarchy() throws NoSuchMethodException {
        MethodInvokingMessageHandlingMember<PA> paHandle = new MethodInvokingMessageHandlingMember<>(
                PA.class.getMethod("paHandle", String.class), CommandMessage.class, String.class,
                parameterResolverFactory, result -> resolveToStream(result, new ClassBasedMessageTypeResolver())
        );
        MethodInvokingMessageHandlingMember<A> aHandle = new MethodInvokingMessageHandlingMember<>(
                A.class.getMethod("aHandle", String.class), CommandMessage.class, String.class,
                parameterResolverFactory, result -> resolveToStream(result, new ClassBasedMessageTypeResolver())
        );
        MethodInvokingMessageHandlingMember<A> aOn = new MethodInvokingMessageHandlingMember<>(
                A.class.getMethod("aOn", Integer.class), EventMessage.class, Integer.class,
                parameterResolverFactory, result -> resolveToStream(result, new ClassBasedMessageTypeResolver())
        );
        MethodInvokingMessageHandlingMember<B> bHandle = new MethodInvokingMessageHandlingMember<>(
                B.class.getMethod("bHandle", Boolean.class), CommandMessage.class, Boolean.class,
                parameterResolverFactory, result -> resolveToStream(result, new ClassBasedMessageTypeResolver())
        );
        MethodInvokingMessageHandlingMember<B> bOn = new MethodInvokingMessageHandlingMember<>(
                B.class.getMethod("bOn", Long.class), EventMessage.class, Long.class,
                parameterResolverFactory, result -> resolveToStream(result, new ClassBasedMessageTypeResolver())
        );
        MethodInvokingMessageHandlingMember<C> cHandle = new MethodInvokingMessageHandlingMember<>(
                C.class.getMethod("cHandle", Boolean.class), CommandMessage.class, Boolean.class,
                parameterResolverFactory, result -> resolveToStream(result, new ClassBasedMessageTypeResolver())
        );
        MethodInvokingMessageHandlingMember<C> cOn = new MethodInvokingMessageHandlingMember<>(
                C.class.getMethod("cOn", Integer.class), EventMessage.class, Integer.class,
                parameterResolverFactory, result -> resolveToStream(result, new ClassBasedMessageTypeResolver())
        );
        MethodInvokingMessageHandlingMember<D> dHandle = new MethodInvokingMessageHandlingMember<>(
                D.class.getMethod("dHandle", String.class), CommandMessage.class, String.class,
                parameterResolverFactory, result -> resolveToStream(result, new ClassBasedMessageTypeResolver())
        );

        Map<Class<?>, SortedSet<MessageHandlingMember<? super A>>> allHandlers = inspector.getAllHandlers();
        assertEquals(5, allHandlers.size());

        //noinspection OptionalGetWithoutIsPresent
        assertEquals(
                paHandle,
                allHandlers.get(PA.class)
                           .first()
                           .unwrap(MethodInvokingMessageHandlingMember.class).get()
        );
        //noinspection OptionalGetWithoutIsPresent
        assertEquals(
                paHandle,
                inspector.getHandlers(PA.class).stream()
                         .findFirst()
                         .flatMap(h -> h.unwrap(MethodInvokingMessageHandlingMember.class)).get()
        );

        assertEquals(asList(aOn, aHandle, paHandle), unwrapToList(allHandlers.get(A.class).stream()));
        assertEquals(asList(aOn, aHandle, paHandle), unwrapToList(inspector.getHandlers(A.class).stream()));

        assertEquals(asList(aOn, bOn, aHandle, bHandle, paHandle), unwrapToList(allHandlers.get(B.class).stream()));
        assertEquals(asList(aOn, bOn, aHandle, bHandle, paHandle), unwrapToList(inspector.getHandlers(B.class).stream()));

        assertEquals(asList(aOn, cOn, aHandle, cHandle, paHandle), unwrapToList(allHandlers.get(C.class).stream()));
        assertEquals(asList(aOn, cOn, aHandle, cHandle, paHandle), unwrapToList(inspector.getHandlers(C.class).stream()));

        assertEquals(
                asList(aOn, bOn, aHandle, bHandle, dHandle, paHandle),
                unwrapToList(allHandlers.get(D.class).stream())
        );
        assertEquals(
                asList(aOn, bOn, aHandle, bHandle, dHandle, paHandle),
                unwrapToList(inspector.getHandlers(D.class).stream())
        );
    }

    @Test
    void doesNotRegisterAbstractHandlersTwice() {
        AnnotatedHandlerInspector<AB> aaInspector = AnnotatedHandlerInspector.inspectType(AB.class,
                                                                                          parameterResolverFactory);

        assertEquals(1, aaInspector.getAllHandlers().size());
        assertEquals(1, (int) aaInspector.getAllHandlers().values().stream().mapToLong(Collection::size).sum());
    }

    private <T extends MessageHandlingMember<?>> List<MethodInvokingMessageHandlingMember<?>> unwrapToList(
            Stream<T> stream
    ) {
        //noinspection OptionalGetWithoutIsPresent
        return stream.map(e -> e.unwrap(MethodInvokingMessageHandlingMember.class)
                                .map(handler -> (MethodInvokingMessageHandlingMember<?>) handler).get())
                     .collect(Collectors.toList());
    }

    @Test
    void interceptors() throws Exception {
        D testTarget = new D();
        EventMessage testEvent = asEventMessage("Hello");
        EventMessage testEventTwo = asEventMessage(1);

        Map<Class<?>, SortedSet<MessageHandlingMember<? super A>>> interceptors = inspector.getAllInterceptors();
        assertEquals(5, interceptors.size());
        assertEquals(1, interceptors.get(PA.class).size());
        assertEquals(1, interceptors.get(A.class).size());
        assertEquals(2, interceptors.get(B.class).size());
        assertEquals(1, interceptors.get(C.class).size());
        assertEquals(2, interceptors.get(D.class).size());

        MessageHandlerInterceptorMemberChain<A> chain = inspector.chainedInterceptor(B.class);

        Optional<MessageHandlingMember<? super A>> optionalHandler = inspector.getHandlers(PA.class).stream().findFirst();
        assertTrue(optionalHandler.isPresent());
        MessageHandlingMember<? super A> resultHandler = optionalHandler.get();

        var result = chain.handle(testEventTwo, StubProcessingContext.forMessage(testEventTwo),
                                  testTarget, resultHandler);
        assertThat(result.hasNextAvailable()).isFalse();
        assertThat(result.isCompleted()).isTrue();
        assertThat(result.error()).containsInstanceOf(MockException.class);
    }

    @SuppressWarnings("unused")
    private static class PA {

        @CommandHandler
        public void paHandle(String a) {
        }

        @MessageHandlerInterceptor
        public void intercept(String e) {

        }
    }

    @SuppressWarnings("unused")
    private static class A extends PA {

        @CommandHandler
        public void aHandle(String a) {
        }

        @EventHandler
        public void aOn(Integer a) {
        }
    }

    @SuppressWarnings("unused")
    private static class B extends A {

        @CommandHandler
        public void bHandle(Boolean a) {
        }

        @EventHandler
        public void bOn(Long b) {
        }

        @MessageHandlerInterceptor
        public void intercept(Integer e, MessageHandlerInterceptorChain<?> chain) {
            throw new MockException("Faking exception in interceptor");
        }
    }

    @SuppressWarnings("unused")
    private static class C extends A {

        @CommandHandler
        public void cHandle(Boolean a) {
        }

        @EventHandler
        public void cOn(Integer a) {
        }
    }

    @SuppressWarnings("unused")
    private static class D extends B {

        @CommandHandler
        public void dHandle(String d) {
        }
    }

    public static abstract class AA {

        @CommandHandler
        public abstract String handleAbstractly(String command);
    }

    public static class AB extends AA {

        @Override
        public String handleAbstractly(String command) {
            return "Some result";
        }
    }
}
