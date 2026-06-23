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

package org.axonframework.messaging.core.interception;

import org.axonframework.common.FutureUtils;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptorMemberChain;
import org.axonframework.messaging.core.unitofwork.LegacyMessageSupportingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link ExceptionHandler} for message handling components.
 *
 * @author Steven van Beelen
 */
class ExceptionHandlerTest {

    private static final String COMMAND_HANDLER_INVOKED = "command";
    private static final String EVENT_HANDLER_INVOKED = "event";
    private static final String QUERY_HANDLER_INVOKED = "query";

    private static final MessageType TEST_COMMAND_TYPE = new MessageType(SomeCommand.class);
    private static final MessageType TEST_QUERY_TYPE = new MessageType(SomeQuery.class);

    private AtomicReference<String> invokedHandler;
    private List<String> invokedExceptionHandlers;

    private ExceptionHandlingComponent messageHandlingComponent;
    private AnnotatedHandlerInspector<ExceptionHandlingComponent> inspector;

    @BeforeEach
    void setUp() {
        invokedHandler = new AtomicReference<>();
        invokedExceptionHandlers = new ArrayList<>();

        messageHandlingComponent = new ExceptionHandlingComponent(invokedHandler, invokedExceptionHandlers);
        inspector = AnnotatedHandlerInspector.inspectType(ExceptionHandlingComponent.class);
    }

    @Test
    void exceptionHandlerIsInvokedForAnCommandHandlerThrowingAnException() {
        CommandMessage command = new GenericCommandMessage(
                TEST_COMMAND_TYPE, new SomeCommand(() -> new RuntimeException("some-exception"))
        );

        try {
            Object result = handle(command);
            assertNull(result);
        } catch (Exception e) {
            assertInstanceOf(IllegalStateException.class, e);
        }

        assertEquals(COMMAND_HANDLER_INVOKED, invokedHandler.get());
        assertTrue(invokedExceptionHandlers.contains("leastSpecificExceptionHandler"));
    }

    @Test
    void exceptionHandlerIsInvokedForAnEventHandlerThrowingAnException() {
        EventMessage event =
                asEventMessage(new SomeEvent(() -> new RuntimeException("some-exception")));

        try {
            Object result = handle(event);
            assertNull(result);
        } catch (Exception e) {
            assertInstanceOf(IllegalStateException.class, e);
        }

        assertEquals(EVENT_HANDLER_INVOKED, invokedHandler.get());
        assertTrue(invokedExceptionHandlers.contains("leastSpecificExceptionHandler"));
    }

    @Test
    void exceptionHandlerIsInvokedForAnQueryHandlerThrowingAnException() {
        QueryMessage query = new GenericQueryMessage(
                TEST_QUERY_TYPE,
                new SomeQuery(() -> new RuntimeException("some-exception")));

        try {
            Object result = handle(query);
            assertNull(result);
        } catch (Exception e) {
            assertInstanceOf(IllegalStateException.class, e);
        }

        assertEquals(QUERY_HANDLER_INVOKED, invokedHandler.get());
        assertTrue(invokedExceptionHandlers.contains("leastSpecificExceptionHandler"));
    }

    @Test
    @Disabled("TODO #3062 - Exception Handler support")
    void exceptionHandlersAreInvokedInHandlerPriorityOrder() {
        CommandMessage command = new GenericCommandMessage(
                TEST_COMMAND_TYPE, new SomeCommand(() -> new IllegalStateException("some-exception"))
        );

        assertThrows(IllegalStateException.class, () -> handle(command));

        assertEquals(COMMAND_HANDLER_INVOKED, invokedHandler.get());

        assertEquals(Arrays.asList("handleIllegalStateExceptionForSomeCommand",
                                   "handleExceptionForSomeCommand",
                                   "handleIllegalStateException",
                                   "handleIllegalStateExceptionThroughAnnotation",
                                   "leastSpecificExceptionHandler"),
                     invokedExceptionHandlers);
    }

    /**
     * This method is a similar approach as followed by the
     * {@link AnnotatedEventHandlingComponent#handle(EventMessage,
     * ProcessingContext)}. Thus, mirroring regular message handling components.
     */
    private Object handle(Message message) throws Exception {
        Optional<MessageHandlingMember<? super ExceptionHandlingComponent>> handler =
                inspector.getHandlers(ExceptionHandlingComponent.class).stream()
                         .filter(h -> h.canHandle(message, new LegacyMessageSupportingContext(message)))
                         .findFirst();
        if (handler.isPresent()) {
            MessageHandlerInterceptorMemberChain<ExceptionHandlingComponent> interceptorChain =
                    inspector.chainedInterceptor(ExceptionHandlingComponent.class);
            ProcessingContext context = StubProcessingContext.forMessage(message);
            return FutureUtils.joinAndUnwrap(
                    interceptorChain.handle(message, context, messageHandlingComponent, handler.get())
                                    .reduce(null, (acc, entry) -> entry.message().payload())
            );
        }
        return null;
    }

    @SuppressWarnings("unused") // suppress not-invoked exception handler warning.
    private record ExceptionHandlingComponent(AtomicReference<String> invokedHandler,
                                              List<String> invokedExceptionHandlers) {

        @ExceptionHandler
        public void leastSpecificExceptionHandler() {
            invokedExceptionHandlers.add("leastSpecificExceptionHandler");
            throw new IllegalStateException("leastSpecificExceptionHandler");
        }

        @ExceptionHandler(resultType = IllegalStateException.class)
        public void handleRuntimeExceptionThroughAnnotation() {
            invokedExceptionHandlers.add("handleIllegalStateExceptionThroughAnnotation");
            throw new IllegalStateException("handleIllegalStateExceptionThroughAnnotation");
        }

        @ExceptionHandler
        public void handleIllegalStateException(IllegalStateException exception) {
            invokedExceptionHandlers.add("handleIllegalStateException");
            throw exception;
        }

        @ExceptionHandler
        public void handleExceptionForSomeCommand(SomeCommand command) {
            invokedExceptionHandlers.add("handleExceptionForSomeCommand");
            throw new IllegalStateException("handleExceptionForSomeCommand");
        }

        @ExceptionHandler
        public void handleRuntimeExceptionForSomeCommand(SomeCommand command, IllegalStateException exception) {
            invokedExceptionHandlers.add("handleIllegalStateExceptionForSomeCommand");
            throw exception;
        }

        @CommandHandler
        public void handle(SomeCommand command) throws Exception {
            invokedHandler.set(COMMAND_HANDLER_INVOKED);
            throw command.exceptionSupplier.get();
        }

        @EventHandler
        public void on(SomeEvent event) throws Exception {
            invokedHandler.set(EVENT_HANDLER_INVOKED);
            throw event.exceptionSupplier.get();
        }

        @QueryHandler
        public SomeQueryResponse handle(SomeQuery query) throws Exception {
            invokedHandler.set(QUERY_HANDLER_INVOKED);
            throw query.exceptionSupplier.get();
        }
    }

    @Nested
    class AnnotatedEventHandlerExceptionHandling {

        @Test
        void exceptionHandlerIsInvokedWhenEventHandlerThrows() {
            // given
            var handler = new EventHandlerWithRethrowingExceptionHandler();
            var component = annotatedEventHandlingComponent(handler);
            var event = asEventMessage("test-payload");

            // when - event handler throws; exception handler is invoked and re-throws
            var result = component.handle(event, StubProcessingContext.forMessage(event));

            // then - handler was invoked, and the re-thrown exception propagates
            assertTrue(handler.exceptionHandlerInvoked);
            assertTrue(result.error().isPresent());
        }

        @Test
        void exceptionHandlerCanSuppressException() {
            // given
            var handler = new EventHandlerWithSuppressingExceptionHandler();
            var component = annotatedEventHandlingComponent(handler);
            var event = asEventMessage("test-payload");

            // when - event handler throws; exception handler swallows the exception
            var result = component.handle(event, StubProcessingContext.forMessage(event));

            // then - exception was suppressed; stream completes without error
            assertTrue(result.error().isEmpty());
        }

        @Test
        void exceptionHandlerNotMatchingExceptionTypeIsSkipped() {
            // given
            var handler = new EventHandlerWithIoExceptionHandlerOnly();
            var component = annotatedEventHandlingComponent(handler);
            var event = asEventMessage("test-payload");

            // when - event handler throws IllegalArgumentException; @ExceptionHandler(resultType = IOException.class) does not match
            var result = component.handle(event, StubProcessingContext.forMessage(event));

            // then - exception handler not invoked; original exception propagates
            assertFalse(handler.exceptionHandlerInvoked);
            assertTrue(result.error().isPresent());
            assertInstanceOf(IllegalArgumentException.class, result.error().get());
        }

        private static AnnotatedEventHandlingComponent<?> annotatedEventHandlingComponent(Object handler) {
            return new AnnotatedEventHandlingComponent<>(
                    handler,
                    ClasspathParameterResolverFactory.forClass(handler.getClass()),
                    ClasspathHandlerDefinition.forClass(handler.getClass()),
                    new AnnotationMessageTypeResolver(),
                    new DelegatingEventConverter(PassThroughConverter.INSTANCE)
            );
        }

        @SuppressWarnings("unused")
        private static class EventHandlerWithRethrowingExceptionHandler {

            boolean exceptionHandlerInvoked = false;

            @EventHandler
            void on(String event) {
                throw new RuntimeException("event handler failed");
            }

            @ExceptionHandler
            void onException(RuntimeException ex) {
                exceptionHandlerInvoked = true;
                throw ex;
            }
        }

        @SuppressWarnings("unused")
        private static class EventHandlerWithSuppressingExceptionHandler {

            @EventHandler
            void on(String event) {
                throw new RuntimeException("event handler failed");
            }

            @ExceptionHandler
            void onException() {
                // return normally — suppresses the exception
            }
        }

        @SuppressWarnings("unused")
        private static class EventHandlerWithIoExceptionHandlerOnly {

            boolean exceptionHandlerInvoked = false;

            @EventHandler
            void on(String event) {
                throw new IllegalArgumentException("wrong type");
            }

            @ExceptionHandler(resultType = IOException.class)
            void onIoException() {
                exceptionHandlerInvoked = true;
            }
        }

        @Test
        void exceptionHandlerReceivesEventMessageAsParameter() {
            // given
            var handler = new EventHandlerWithEventMessageParameter();
            var component = annotatedEventHandlingComponent(handler);
            var event = asEventMessage("test-payload");

            // when
            var result = component.handle(event, StubProcessingContext.forMessage(event));

            // then - exception handler received the full EventMessage
            assertSame(event, handler.capturedMessage);
            assertTrue(result.error().isEmpty());
        }

        @Test
        void exceptionHandlerReceivesBothEventMessageAndExceptionAsParameters() {
            // given
            var handler = new EventHandlerWithEventMessageAndExceptionParameters();
            var component = annotatedEventHandlingComponent(handler);
            var event = asEventMessage("test-payload");

            // when
            var result = component.handle(event, StubProcessingContext.forMessage(event));

            // then - exception handler received both the full EventMessage and the exception
            assertSame(event, handler.capturedMessage);
            assertInstanceOf(RuntimeException.class, handler.capturedException);
            assertTrue(result.error().isEmpty());
        }

        @Test
        void exceptionHandlerWithEventMessageParameterDoesNotMatchWrongMessageType() {
            // given - a component that only handles CommandMessage in its exception handler
            var handler = new EventHandlerWithCommandMessageExceptionHandler();
            var component = annotatedEventHandlingComponent(handler);
            var event = asEventMessage("test-payload");

            // when - an EventMessage arrives, but the exception handler expects a CommandMessage
            var result = component.handle(event, StubProcessingContext.forMessage(event));

            // then - exception handler is not invoked; original exception propagates
            assertFalse(handler.exceptionHandlerInvoked);
            assertTrue(result.error().isPresent());
        }

        @Test
        void exceptionHandlerWithQueryMessageParameterDoesNotMatchEventMessage() {
            // given - a projection whose exception handler targets only QueryMessage failures
            var handler = new ProjectionWithQueryMessageExceptionHandler();
            var component = annotatedEventHandlingComponent(handler);
            var event = asEventMessage("test-payload");

            // when - an EventMessage arrives and the event handler throws
            var result = component.handle(event, StubProcessingContext.forMessage(event));

            // then - the QueryMessage-scoped exception handler is not invoked; original exception propagates
            assertFalse(handler.exceptionHandlerInvoked);
            assertTrue(result.error().isPresent());
        }

        @SuppressWarnings("unused")
        private static class EventHandlerWithEventMessageParameter {

            EventMessage capturedMessage;

            @EventHandler
            void on(String event) {
                throw new RuntimeException("event handler failed");
            }

            @ExceptionHandler
            void onException(EventMessage message) {
                capturedMessage = message;
                // return normally — suppresses the exception
            }
        }

        @SuppressWarnings("unused")
        private static class EventHandlerWithEventMessageAndExceptionParameters {

            EventMessage capturedMessage;
            RuntimeException capturedException;

            @EventHandler
            void on(String event) {
                throw new RuntimeException("event handler failed");
            }

            @ExceptionHandler
            void onException(EventMessage message, RuntimeException exception) {
                capturedMessage = message;
                capturedException = exception;
                // return normally — suppresses the exception
            }
        }

        @SuppressWarnings("unused")
        private static class EventHandlerWithCommandMessageExceptionHandler {

            boolean exceptionHandlerInvoked = false;

            @EventHandler
            void on(String event) {
                throw new RuntimeException("event handler failed");
            }

            @ExceptionHandler
            void onException(CommandMessage message) {
                exceptionHandlerInvoked = true;
            }
        }

        @SuppressWarnings("unused")
        private static class ProjectionWithQueryMessageExceptionHandler {

            boolean exceptionHandlerInvoked = false;

            @EventHandler
            void on(String event) {
                throw new RuntimeException("event handler failed");
            }

            @ExceptionHandler
            void onQueryException(QueryMessage message) {
                // only intended for query-related failures in a projection
                exceptionHandlerInvoked = true;
            }
        }
    }

    private record SomeCommand(Supplier<Exception> exceptionSupplier) {

    }

    private record SomeEvent(Supplier<Exception> exceptionSupplier) {

    }

    private record SomeQuery(Supplier<Exception> exceptionSupplier) {

    }

    private static class SomeQueryResponse {

    }
}
