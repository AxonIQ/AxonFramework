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

package org.axonframework.messaging.commandhandling.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.NoHandlerForCommandException;
import org.axonframework.messaging.commandhandling.interception.CommandMessageHandlerInterceptorChain;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStream.Entry;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

/**
 * Test to validate the {@link AnnotatedCommandHandlingComponent}.
 *
 * @author Allard Buijze
 */
class AnnotatedCommandHandlingComponentTest {

    private static final MessageType TEST_TYPE = new MessageType("command");

    private final AtomicInteger callCount = new AtomicInteger();

    private MyCommandHandler annotatedCommandHandler;

    private AnnotatedCommandHandlingComponent<MyCommandHandler> testSubject;

    @BeforeEach
    void setUp() {
        annotatedCommandHandler = new MyCommandHandler();
        testSubject = new AnnotatedCommandHandlingComponent<>(
                annotatedCommandHandler,
                ClasspathParameterResolverFactory.forClass(annotatedCommandHandler.getClass()),
                ClasspathHandlerDefinition.forClass(annotatedCommandHandler.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        CommandBus commandBus = mock(CommandBus.class);
        when(commandBus.subscribe(any(QualifiedName.class), any())).thenReturn(commandBus);
        when(commandBus.subscribe(anySet(), any())).thenReturn(commandBus);
    }

    @Test
    void subscribesCommandHandlerWithCommandName() {
        Object annotatedCommandHandler = new Object() {
            @SuppressWarnings("unused")
            @CommandHandler(commandName = "myCommandName")
            public void handle(String command) {
                // Unimportant
            }
        };
        MessageTypeResolver messageTypeResolver = spy(new AnnotationMessageTypeResolver());
        AnnotatedCommandHandlingComponent<Object> annotatedComponent = new AnnotatedCommandHandlingComponent<>(
                annotatedCommandHandler,
                ClasspathParameterResolverFactory.forClass(annotatedCommandHandler.getClass()),
                ClasspathHandlerDefinition.forClass(annotatedCommandHandler.getClass()),
                messageTypeResolver,
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        Set<QualifiedName> supportedCommands = annotatedComponent.supportedCommands();
        assertThat(supportedCommands).hasSize(1);
        assertThat(supportedCommands).contains(new QualifiedName("myCommandName"));
        verifyNoInteractions(messageTypeResolver);
    }

    @Test
    void subscribesCommandHandlerThroughMessageTypeResolverWhenCommandNameIsEmpty() {
        QualifiedName expectedName = new QualifiedName("defaultName");

        Object annotatedCommandHandler = new Object() {
            @SuppressWarnings({"unused", "DefaultAnnotationParam"})
            @CommandHandler(commandName = "") // Deliberately empty to give control to the MessageTypeResolver
            public void handle(String command) {
                // Unimportant
            }
        };
        AnnotatedCommandHandlingComponent<Object> annotatedComponent = new AnnotatedCommandHandlingComponent<>(
                annotatedCommandHandler,
                ClasspathParameterResolverFactory.forClass(annotatedCommandHandler.getClass()),
                ClasspathHandlerDefinition.forClass(annotatedCommandHandler.getClass()),
                payloadType -> Optional.of(new MessageType(expectedName)),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        Set<QualifiedName> supportedCommands = annotatedComponent.supportedCommands();
        assertThat(supportedCommands).hasSize(1);
        assertThat(supportedCommands).contains(expectedName);
    }

    @Test
    void handlerDispatchingVoidReturnType() {
        CommandMessage testCommand = new GenericCommandMessage(new MessageType(String.class), "myStringPayload");

        MessageStream.Single<CommandResultMessage> resultStream =
                testSubject.handle(testCommand, StubProcessingContext.forMessage(testCommand));

        assertTrue(resultStream.isCompleted());
        assertFalse(resultStream.hasNextAvailable());
        assertEquals(1, annotatedCommandHandler.voidHandlerInvoked);
        assertEquals(0, annotatedCommandHandler.returningHandlerInvoked);
    }

    @Test
    void handlerDispatchingWithReturnType() {
        CommandMessage testCommand = new GenericCommandMessage(new MessageType(Long.class), 1L);

        Object result = testSubject.handle(testCommand, StubProcessingContext.forMessage(testCommand))
                                   .first()
                                   .asCompletableFuture()
                                   .join()
                                   .message()
                                   .payload();

        assertEquals(1L, result);
        assertEquals(0, annotatedCommandHandler.voidHandlerInvoked);
        assertEquals(1, annotatedCommandHandler.returningHandlerInvoked);
    }

    @Test
    void handlerDispatchingWithCustomCommandName() {
        CommandMessage testCommand =
                new GenericCommandMessage(new GenericMessage(new MessageType("almostLong"), 1L));

        Object result = testSubject.handle(testCommand, StubProcessingContext.forMessage(testCommand))
                                   .first()
                                   .asCompletableFuture()
                                   .join()
                                   .message()
                                   .payload();

        assertEquals(1L, result);
        assertEquals(0, annotatedCommandHandler.voidHandlerInvoked);
        assertEquals(0, annotatedCommandHandler.returningHandlerInvoked);
        assertEquals(1, annotatedCommandHandler.almostDuplicateReturningHandlerInvoked);
    }

    @Test
    void handlerDispatchingThrowingException() {
        try {
            GenericCommandMessage command = new GenericCommandMessage(new MessageType(HashSet.class),
                                                                      new HashSet<>());
            testSubject.handle(command, StubProcessingContext.forMessage(command))
                       .first()
                       .asCompletableFuture()
                       .join();

            fail("Expected exception");
        } catch (Exception ex) {
            assertEquals(Exception.class, ex.getCause().getClass());
            return;
        }
        fail("Shouldn't make it till here");
    }

    @Test
    void handleNoHandlerForCommand() {
        CommandMessage command = new GenericCommandMessage(TEST_TYPE, new LinkedList<>());

        var exception = assertThrows(CompletionException.class,
                                     () -> testSubject.handle(command, mock(ProcessingContext.class)).first()
                                                      .asCompletableFuture().join());
        assertInstanceOf(NoHandlerForCommandException.class, exception.getCause());
    }

    @Disabled("Reintegrate as part of #3485")
    @Test
    void messageHandlerInterceptorAnnotatedMethodsAreSupportedForCommandHandlingComponents() {
        CommandMessage testCommandMessage = new GenericCommandMessage(new MessageType(String.class), "");
        List<CommandMessage> withInterceptor = new ArrayList<>();
        List<CommandMessage> withoutInterceptor = new ArrayList<>();
        annotatedCommandHandler =
                new MyInterceptingCommandHandler(withoutInterceptor, withInterceptor, new ArrayList<>());
        testSubject = new AnnotatedCommandHandlingComponent<>(
                annotatedCommandHandler,
                ClasspathParameterResolverFactory.forClass(annotatedCommandHandler.getClass()),
                ClasspathHandlerDefinition.forClass(annotatedCommandHandler.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        Object result = testSubject.handle(testCommandMessage, mock(ProcessingContext.class))
                                   .first()
                                   .asCompletableFuture()
                                   .join()
                                   .message()
                                   .payload();

        assertNull(result);
        // TODO #3485 The interceptor chain is not yet implemented fully through the MessageStream.
        //  Hence, this test does not end up in the message handler.
//        assertEquals(1, annotatedCommandHandler.voidHandlerInvoked);
        assertEquals(Collections.singletonList(testCommandMessage), withInterceptor);
        assertEquals(Collections.singletonList(testCommandMessage), withoutInterceptor);
    }

    @Test
    @Disabled("TODO #3062 - Exception Handler support")
    void exceptionHandlerAnnotatedMethodsAreSupportedForCommandHandlingComponents() {
        CommandMessage testCommandMessage = new GenericCommandMessage(TEST_TYPE, new ArrayList<>());
        List<Exception> interceptedExceptions = new ArrayList<>();
        annotatedCommandHandler =
                new MyInterceptingCommandHandler(new ArrayList<>(), new ArrayList<>(), interceptedExceptions);
        testSubject = new AnnotatedCommandHandlingComponent<>(
                annotatedCommandHandler,
                ClasspathParameterResolverFactory.forClass(annotatedCommandHandler.getClass()),
                ClasspathHandlerDefinition.forClass(annotatedCommandHandler.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        try {
            testSubject.handle(testCommandMessage, mock(ProcessingContext.class));
            fail("Expected exception to be thrown");
        } catch (Exception e) {

        }

        assertFalse(interceptedExceptions.isEmpty());
        assertEquals(1, interceptedExceptions.size());
        Exception interceptedException = interceptedExceptions.getFirst();
        assertInstanceOf(RuntimeException.class, interceptedException);
        assertEquals("Some exception", interceptedException.getMessage());
    }

    @Nested
    class GivenAnAnnotatedInterfaceMethod {

        interface I {

            @CommandHandler
            int handle(Integer event);
        }

        @Nested
        class WhenImplementedByAnnotatedInstanceMethod {

            class T implements I {

                @Override
                @CommandHandler
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @SuppressWarnings("RedundantMethodOverride")
                    @Override
                    @CommandHandler
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @Override
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }
        }

        @Nested
        class WhenImplementedByUnannotatedInstanceMethod {

            class T implements I {

                @Override
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @Override
                    @CommandHandler
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @SuppressWarnings("RedundantMethodOverride")
                    @Override
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }
        }
    }

    @Nested
    class GivenAnUnannotatedInterfaceMethod {

        interface I {

            int handle(Integer event);
        }

        @Nested
        class WhenImplementedByAnnotatedInstanceMethod {

            class T implements I {

                @Override
                @CommandHandler
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @SuppressWarnings("RedundantMethodOverride")
                    @Override
                    @CommandHandler
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @Override
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }
        }

        @Nested
        class WhenImplementedByUnannotatedInstanceMethod {

            class T implements I {

                @Override
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldNotCallAnything() {
                assertNotCalled(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @Override
                    @CommandHandler
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @SuppressWarnings("RedundantMethodOverride")
                    @Override
                    public int handle(Integer event) {
                        return callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldNotCallAnything() {
                    assertNotCalled(new U());
                }
            }
        }
    }

    @Nested
    class GivenAnAnnotatedInstanceMethod {

        class T {

            @CommandHandler
            public int handle(Integer event) {
                return callCount.incrementAndGet();
            }
        }

        @Test
        void shouldCallHandlerOnlyOnce() {
            assertCalledOnlyOnce(new T());
        }

        @Nested
        class WhenOverriddenAndAnnotatedInASubclass {

            class U extends T {

                @SuppressWarnings("RedundantMethodOverride")
                @Override
                @CommandHandler
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }

        @Nested
        class WhenNotOverriddenInSubclass {

            class U extends T {

            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }

        @Nested
        class WhenOverriddenButNotAnnotatedInASubclass {

            class U extends T {

                @Override
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }
    }

    @Nested
    class GivenAnUnannotatedInstanceMethod {

        class T {

            public int handle(Integer event) {
                return callCount.incrementAndGet();
            }
        }

        @Test
        void shouldNotCallAnything() {
            assertNotCalled(new T());
        }

        @Nested
        class WhenOverriddenAndAnnotatedInASubclass {

            class U extends T {

                @Override
                @CommandHandler
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }

        @Nested
        class WhenOverriddenButNotAnnotatedInASubclass {

            class U extends T {

                @SuppressWarnings("RedundantMethodOverride")
                @Override
                public int handle(Integer event) {
                    return callCount.incrementAndGet();
                }
            }

            @Test
            void shouldNotCallAnything() {
                assertNotCalled(new U());
            }
        }
    }

    private void assertCalledOnlyOnce(Object handlerInstance) {
        AnnotatedCommandHandlingComponent<?> testSubject = new AnnotatedCommandHandlingComponent<>(
                handlerInstance,
                ClasspathParameterResolverFactory.forClass(handlerInstance.getClass()),
                ClasspathHandlerDefinition.forClass(handlerInstance.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        CommandMessage testCommand = new GenericCommandMessage(new MessageType(Integer.class), 42);
        Integer result = testSubject.handle(testCommand, StubProcessingContext.forMessage(testCommand))
                                    .first()
                                    .asCompletableFuture()
                                    .join()
                                    .message()
                                    .payloadAs(Integer.class);

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(result).isEqualTo(1);
    }

    private void assertNotCalled(Object handlerInstance) {
        AnnotatedCommandHandlingComponent<?> testSubject = new AnnotatedCommandHandlingComponent<>(
                handlerInstance,
                ClasspathParameterResolverFactory.forClass(handlerInstance.getClass()),
                ClasspathHandlerDefinition.forClass(handlerInstance.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
        );

        CommandMessage testCommand = new GenericCommandMessage(new MessageType(Integer.class), 42);

        CompletableFuture<Entry<CommandResultMessage>> future =
                testSubject.handle(testCommand, StubProcessingContext.forMessage(testCommand))
                           .first()
                           .asCompletableFuture();

        assertThatThrownBy(future::join).isInstanceOf(CompletionException.class)
                                        .cause()
                                        .isInstanceOf(NoHandlerForCommandException.class);

        assertThat(callCount.get()).isEqualTo(0);
    }

    @SuppressWarnings("unused")
    private static class MyCommandHandler {

        private int voidHandlerInvoked;
        private int returningHandlerInvoked;
        private int almostDuplicateReturningHandlerInvoked;

        @SuppressWarnings({"UnusedDeclaration"})
        @CommandHandler
        public void myVoidHandler(String stringCommand) {
            voidHandlerInvoked++;
        }

        @CommandHandler(commandName = "almostLong")
        public Long myAlmostDuplicateReturningHandler(Long longCommand) {
            almostDuplicateReturningHandlerInvoked++;
            return longCommand;
        }

        @CommandHandler
        public Long myReturningHandler(Long longCommand) {
            returningHandlerInvoked++;
            return longCommand;
        }

        @SuppressWarnings({"UnusedDeclaration"})
        @CommandHandler
        public void exceptionThrowingHandler(HashSet<Object> o) throws Exception {
            throw new Exception("Some exception");
        }

        @SuppressWarnings({"UnusedDeclaration"})
        @CommandHandler
        public void exceptionThrowingHandler(ArrayList<Object> o) {
            throw new RuntimeException("Some exception");
        }
    }

    @SuppressWarnings("unused")
    private static class MyInterceptingCommandHandler extends MyCommandHandler {

        private final List<CommandMessage> interceptedWithoutInterceptorChain;
        private final List<CommandMessage> interceptedWithInterceptorChain;
        private final List<Exception> interceptedExceptions;

        private MyInterceptingCommandHandler(List<CommandMessage> interceptedWithoutInterceptorChain,
                                             List<CommandMessage> interceptedWithInterceptorChain,
                                             List<Exception> interceptedExceptions) {
            this.interceptedWithoutInterceptorChain = interceptedWithoutInterceptorChain;
            this.interceptedWithInterceptorChain = interceptedWithInterceptorChain;
            this.interceptedExceptions = interceptedExceptions;
        }

        @MessageHandlerInterceptor
        public void interceptAny(CommandMessage command) {
            interceptedWithoutInterceptorChain.add(command);
        }

        @MessageHandlerInterceptor
        public Object interceptAny(CommandMessage command, ProcessingContext context,
                                   CommandMessageHandlerInterceptorChain chain) {
            interceptedWithInterceptorChain.add(command);
            return chain.proceed(command, context);
        }

        @ExceptionHandler
        public void handle(Exception exception) {
            interceptedExceptions.add(exception);
        }
    }
}
