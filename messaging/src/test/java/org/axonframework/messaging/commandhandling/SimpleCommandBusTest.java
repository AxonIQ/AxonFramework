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

package org.axonframework.messaging.commandhandling;

import org.jspecify.annotations.NonNull;
import org.axonframework.common.StubExecutor;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.util.MockException;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.axonframework.messaging.core.MessagingTestUtils.asCommandResultMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link SimpleCommandBus}.
 *
 * @author Allard Buijze
 */
class SimpleCommandBusTest {

    private static final String PAYLOAD = "Say hi!";
    private static final CommandMessage TEST_COMMAND =
            new GenericCommandMessage(new MessageType("command"), PAYLOAD);
    private static final QualifiedName COMMAND_NAME = TEST_COMMAND.type().qualifiedName();

    private SimpleCommandBus testSubject;
    private StubExecutor executor;

    @BeforeEach
    void setUp() {
        this.executor = new StubExecutor();
        this.testSubject = new SimpleCommandBus(
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE, c -> c.workScheduler(executor))
        );
    }

    @Test
    void dispatchCommandHandlerSubscribed() throws Exception {
        testSubject.subscribe(COMMAND_NAME, new StubCommandHandler("Hi!"));

        CompletableFuture<? extends Message> actual =
                testSubject.dispatch(TEST_COMMAND, StubProcessingContext.forMessage(TEST_COMMAND));

        assertEquals("Hi!", actual.get().payload());
    }

    @Test
    void dispatchCommandHandlerSubscribedAndReturnEmptyResultsInMessageWithNullPayload() throws Exception {
        testSubject.subscribe(COMMAND_NAME, (m, c) -> MessageStream.empty().cast());

        CompletableFuture<? extends Message> actual =
                testSubject.dispatch(TEST_COMMAND, StubProcessingContext.forMessage(TEST_COMMAND));

        Message actualMessage = actual.get();
        assertNotNull(actualMessage);
        assertNull(actualMessage.payload());
    }

    @Test
    void dispatchCommandImplicitProcessingContextIsCommittedOnReturnValue() {
        final AtomicReference<ProcessingContext> contextRef = new AtomicReference<>();
        testSubject.subscribe(COMMAND_NAME,
                              (message, processingContext) -> {
                                  contextRef.set(processingContext);
                                  return MessageStream.just(asCommandResultMessage(message));
                              });
        var actual = testSubject.dispatch(TEST_COMMAND, StubProcessingContext.forMessage(TEST_COMMAND));
        assertTrue(actual.isDone());
        assertFalse(actual.isCompletedExceptionally());
        Message actualResult = actual.join();
        assertEquals(PAYLOAD, actualResult.payload());
        assertNotNull(contextRef.get());
    }

    @Test
    void dispatchCommandImplicitProcessingContextIsRolledBackOnException() {
        final AtomicReference<ProcessingContext> contextRef = new AtomicReference<>();
        testSubject.subscribe(COMMAND_NAME,
                              (message, processingContext) -> {
                                  contextRef.set(processingContext);
                                  throw new RuntimeException();
                              });
        testSubject.dispatch(TEST_COMMAND, StubProcessingContext.forMessage(TEST_COMMAND));
        assertTrue(contextRef.get().isError());
    }

    @Test
    void dispatchCommandNoHandlerSubscribed() {
        var result = testSubject.dispatch(TEST_COMMAND, StubProcessingContext.forMessage(TEST_COMMAND));

        assertTrue(result.isCompletedExceptionally());
        CompletionException actualException = assertThrows(CompletionException.class, result::join);
        assertInstanceOf(NoHandlerForCommandException.class, actualException.getCause());
    }

    @Test
    void asyncHandlerCompletion() throws Exception {
        var ourFutureIsBright = new CompletableFuture<>();
        testSubject.subscribe(COMMAND_NAME, new StubCommandHandler(ourFutureIsBright));

        var actual = testSubject.dispatch(TEST_COMMAND, StubProcessingContext.forMessage(TEST_COMMAND));

        assertFalse(actual.isDone());
        CompletableFuture<String> stringCompletableFuture = actual.thenApply(crm -> Thread.currentThread().getName());

        Thread t = new Thread(() -> ourFutureIsBright.complete("42"));
        t.start();
        t.join();

        assertTrue(stringCompletableFuture.isDone());
        assertEquals(t.getName(), stringCompletableFuture.get());
    }

    @Test
    void asyncHandlerVirtual() throws Exception {
        var ourFutureIsBright = new CompletableFuture<>();
        testSubject.subscribe(COMMAND_NAME, new StubCommandHandler(ourFutureIsBright));

        var actual = testSubject.dispatch(TEST_COMMAND, StubProcessingContext.forMessage(TEST_COMMAND));

        assertFalse(actual.isDone());
        CompletableFuture<String> stringCompletableFuture = actual.thenApply(crm -> Thread.currentThread().getName());

        Thread t = Thread.startVirtualThread(() -> ourFutureIsBright.complete("42"));
        t.join();

        assertTrue(stringCompletableFuture.isDone());
        assertEquals(t.getName(), stringCompletableFuture.get());
    }

    @Test
    void handlerInvokedOnExecutorThread() {
        executor.enqueueTasks();

        var commandHandler = spy(new StubCommandHandler("ok"));
        CommandMessage command = TEST_COMMAND;
        testSubject.subscribe(command.type().qualifiedName(), commandHandler);

        var actual = testSubject.dispatch(command, StubProcessingContext.forMessage(TEST_COMMAND));

        verify(commandHandler, never()).handle(eq(command), any());
        assertFalse(actual.isDone());

        executor.runAll();

        verify(commandHandler).handle(eq(command), any());
        assertTrue(actual.isDone());
    }

    @Test
    void exceptionThrownFromHandlerReturnedInCompletableFuture() {
        var commandHandler = new StubCommandHandler("ok") {
            @Override
            public MessageStream.@NonNull Single<CommandResultMessage> handle(@NonNull CommandMessage command,
                                                                              @NonNull ProcessingContext processingContext) {
                throw new MockException("Simulating exception");
            }
        };
        CommandMessage command = TEST_COMMAND;
        testSubject.subscribe(command.type().qualifiedName(), commandHandler);

        CompletableFuture<? extends Message> actual =
                testSubject.dispatch(command, StubProcessingContext.forMessage(
                        TEST_COMMAND));

        assertTrue(actual.isCompletedExceptionally());
        ExecutionException exception = assertThrows(ExecutionException.class, actual::get);
        assertInstanceOf(MockException.class, exception.getCause());
        assertEquals("Simulating exception", exception.getCause().getMessage());
    }

    @Test
    void exceptionalStreamFromHandlerReturnedInCompletableFuture() {
        var commandHandler = new StubCommandHandler(new MockException("Simulating exception"));
        CommandMessage command = TEST_COMMAND;
        testSubject.subscribe(command.type().qualifiedName(), commandHandler);

        CompletableFuture<? extends Message> actual =
                testSubject.dispatch(command, StubProcessingContext.forMessage(TEST_COMMAND));

        assertTrue(actual.isCompletedExceptionally());
        ExecutionException exception = assertThrows(ExecutionException.class, actual::get);
        assertInstanceOf(MockException.class, exception.getCause());
        assertEquals("Simulating exception", exception.getCause().getMessage());
    }

    @Test
    void exceptionIsThrownWhenNoHandlerIsRegistered() {
        CompletableFuture<? extends Message> actual =
                testSubject.dispatch(TEST_COMMAND, StubProcessingContext.forMessage(
                        TEST_COMMAND));

        assertTrue(actual.isCompletedExceptionally());
        ExecutionException exception = assertThrows(ExecutionException.class, actual::get);
        assertInstanceOf(NoHandlerForCommandException.class, exception.getCause());
    }

    @Test
    void duplicateRegistrationIsRejected() {
        var handler1 = mock(CommandHandler.class);
        var handler2 = mock(CommandHandler.class);
        testSubject.subscribe(COMMAND_NAME, handler1);
        assertThrows(DuplicateCommandHandlerSubscriptionException.class,
                     () -> testSubject.subscribe(COMMAND_NAME, handler2));
    }

    @Test
    void duplicateRegistrationForSameHandlerIsAllowed() {
        var handler = mock(CommandHandler.class);
        testSubject.subscribe(COMMAND_NAME, handler);
        assertDoesNotThrow(() -> testSubject.subscribe(COMMAND_NAME, handler));
    }

    @Test
    void describeReturnsRegisteredComponents() {
        UnitOfWorkFactory unitOfWorkFactory = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE);
        testSubject = new SimpleCommandBus(unitOfWorkFactory);
        var handler1 = mock(CommandHandler.class);
        var handler2 = mock(CommandHandler.class);
        testSubject.subscribe(COMMAND_NAME, handler1);
        QualifiedName handlerTwoName = new QualifiedName("test2");
        testSubject.subscribe(handlerTwoName, handler2);

        ComponentDescriptor mockComponentDescriptor = mock(ComponentDescriptor.class);
        testSubject.describeTo(mockComponentDescriptor);

        verify(mockComponentDescriptor).describeProperty("unitOfWorkFactory", unitOfWorkFactory);
        verify(mockComponentDescriptor)
                .describeProperty("subscriptions", Map.of(COMMAND_NAME, handler1, handlerTwoName, handler2));
    }

    private static class StubCommandHandler implements CommandHandler {

        private final Object result;

        public StubCommandHandler(Object result) {
            this.result = result;
        }

        @Override
        public MessageStream.@NonNull Single<CommandResultMessage> handle(@NonNull CommandMessage command,
                                                                          @NonNull ProcessingContext processingContext) {
            if (result instanceof Throwable error) {
                return MessageStream.failed(error);
            } else if (result instanceof CompletableFuture<?> future) {
                return MessageStream.fromFuture(future.thenApply(
                        r -> new GenericCommandResultMessage(new MessageType(r.getClass()), r)
                ));
            } else {
                return MessageStream.just(
                        new GenericCommandResultMessage(new MessageType(result.getClass()), result)
                );
            }
        }
    }
}
