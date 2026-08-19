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

package org.axonframework.messaging.commandhandling.gateway;

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandPriorityCalculator;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.commandhandling.annotation.AnnotationRoutingStrategy;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.common.util.MockException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link DefaultCommandGateway}.
 *
 * @author Allard Buijze
 */
class DefaultCommandGatewayTest {

    private static final MessageTypeResolver TEST_MESSAGE_NAME_RESOLVER =
            payloadType -> Optional.of(new MessageType(payloadType.getSimpleName()));

    private static final Metadata TEST_METADATA = Metadata.with("key", "value");

    private CommandBus mockCommandBus;

    private DefaultCommandGateway testSubject;

    @BeforeEach
    void setUp() {
        mockCommandBus = mock(CommandBus.class);

        testSubject = new DefaultCommandGateway(mockCommandBus,
                                                TEST_MESSAGE_NAME_RESOLVER,
                                                CommandPriorityCalculator.defaultCalculator(),
                                                new AnnotationRoutingStrategy());
    }

    @Test
    void wrapsObjectIntoCommandMessage() throws ExecutionException, InterruptedException {
        when(mockCommandBus.dispatch(any(), any())).thenAnswer(i -> CompletableFuture.completedFuture(
                new GenericCommandResultMessage(new MessageType("result"), "OK")
        ));
        TestPayload payload = new TestPayload();
        CommandResult result = testSubject.send(payload);
        verify(mockCommandBus).dispatch(
                argThat(m -> {
                    Object resultPayload = m.payload();
                    return resultPayload != null && resultPayload.equals(payload);
                }),
                isNull()
        );
        assertEquals("OK", result.getResultMessage().get().payload());
    }

    @Test
    void dispatchReturnsExceptionallyCompletedFutureWhenCommandBusCompletesExceptionally() {
        when(mockCommandBus.dispatch(any(),
                                     any())).thenAnswer(i -> CompletableFuture.failedFuture(new MockException()));
        TestPayload payload = new TestPayload();
        CommandResult result = testSubject.send(payload);
        verify(mockCommandBus).dispatch(
                argThat(m -> {
                    Object resultPayload = m.payload();
                    return resultPayload != null && resultPayload.equals(payload);
                }),
                isNull()
        );
        assertTrue(result.getResultMessage().isCompletedExceptionally());
    }

    @Test
    void dispatchReturnsExceptionallyCompletedFutureWhenCommandBusReturnsExceptionalMessage() {
        when(mockCommandBus.dispatch(any(), any()))
                .thenAnswer(i -> CompletableFuture.failedFuture(new MockException()));
        TestPayload payload = new TestPayload();
        CommandResult result = testSubject.send(payload);
        verify(mockCommandBus).dispatch(
                argThat(m -> {
                    Object resultPayload = m.payload();
                    return resultPayload != null && resultPayload.equals(payload);
                }),
                isNull()
        );
        assertTrue(result.getResultMessage().isCompletedExceptionally());
    }

    @Test
    void resolvesMessageTypeUsingMessageNameResolver() throws ExecutionException, InterruptedException {
        // given
        when(mockCommandBus.dispatch(any(), any())).thenAnswer(i -> CompletableFuture.completedFuture(
                new GenericCommandResultMessage(new MessageType("result"), "OK")
        ));

        // when
        TestPayload payload = new TestPayload();
        CommandResult result = testSubject.send(payload);

        // then
        var expectedMessageType = new MessageType("TestPayload");
        verify(mockCommandBus).dispatch(argThat(m -> m.type().equals(expectedMessageType)), isNull());
        assertEquals("OK", result.getResultMessage().get().payload());
    }

    @Test
    void passCommandMessageAsIs() throws ExecutionException, InterruptedException {
        // given
        when(mockCommandBus.dispatch(any(), any())).thenAnswer(i -> CompletableFuture.completedFuture(
                new GenericCommandResultMessage(new MessageType("result"), "OK")
        ));

        // when
        TestPayload payload = new TestPayload();
        CommandMessage testCommand = new GenericCommandMessage(
                new MessageType("command"), payload, Metadata.emptyInstance(), "routingKey", 42
        );

        CommandResult result = testSubject.send(testCommand);

        // then
        ArgumentCaptor<CommandMessage> commandCaptor = ArgumentCaptor.forClass(CommandMessage.class);
        verify(mockCommandBus).dispatch(commandCaptor.capture(), isNull());
        CommandMessage resultCommand = commandCaptor.getValue();
        assertThat(testCommand.identifier()).isEqualTo(resultCommand.identifier());
        assertThat(testCommand.type()).isEqualTo(resultCommand.type());
        assertThat(testCommand.payload()).isEqualTo(resultCommand.payload());
        assertThat(testCommand.payloadType()).isEqualTo(resultCommand.payloadType());
        assertThat(testCommand.metadata()).isEqualTo(resultCommand.metadata());
        assertThat(testCommand.routingKey()).isEqualTo(resultCommand.routingKey());
        assertThat(testCommand.priority()).isEqualTo(resultCommand.priority());
        assertEquals("OK", result.getResultMessage().get().payload());
    }

    @Test
    void sendAndWaitReturnsExpectedResult() {
        // given...
        String expectedResult = "OK";
        when(mockCommandBus.dispatch(any(), any())).thenAnswer(i -> CompletableFuture.completedFuture(
                new GenericCommandResultMessage(new MessageType("result"), expectedResult)
        ));
        TestPayload payload = new TestPayload();
        // when...
        Object result = testSubject.sendAndWait(payload);
        // then...
        assertThat(result).isEqualTo(expectedResult);
        verify(mockCommandBus).dispatch(
                argThat(m -> {
                    Object resultPayload = m.payload();
                    return resultPayload != null && resultPayload.equals(payload);
                }),
                isNull()
        );
    }

    @Test
    void sendAndWaitWithContextPassesAlongContextAndReturnsExpectedResult() {
        // given...
        String expectedResult = "OK";
        when(mockCommandBus.dispatch(any(), any())).thenAnswer(i -> CompletableFuture.completedFuture(
                new GenericCommandResultMessage(new MessageType("result"), expectedResult)
        ));
        TestPayload payload = new TestPayload();
        ProcessingContext testContext = new StubProcessingContext();
        // when...
        Object result = testSubject.sendAndWait(payload, testContext);
        // then...
        assertThat(result).isEqualTo(expectedResult);
        verify(mockCommandBus).dispatch(
                argThat(m -> {
                    Object resultPayload = m.payload();
                    return resultPayload != null && resultPayload.equals(payload);
                }),
                eq(testContext)
        );
    }

    @Test
    void sendAndWaitForResultTypeReturnsExpectedResult() {
        // given...
        String expectedResult = "OK";
        when(mockCommandBus.dispatch(any(), any())).thenAnswer(i -> CompletableFuture.completedFuture(
                new GenericCommandResultMessage(new MessageType("result"), expectedResult)
        ));
        TestPayload payload = new TestPayload();
        // when...
        String result = testSubject.sendAndWait(payload, String.class);
        // then...
        assertThat(result).isEqualTo(expectedResult);
        verify(mockCommandBus).dispatch(
                argThat(m -> {
                    Object resultPayload = m.payload();
                    return resultPayload != null && resultPayload.equals(payload);
                }),
                isNull()
        );
    }

    @Test
    void sendAndWaitForResultTypeAndContextPassesAlongContextAndReturnsExpectedResult() {
        // given...
        String expectedResult = "OK";
        when(mockCommandBus.dispatch(any(), any())).thenAnswer(i -> CompletableFuture.completedFuture(
                new GenericCommandResultMessage(new MessageType("result"), expectedResult)
        ));
        TestPayload payload = new TestPayload();
        ProcessingContext testContext = new StubProcessingContext();
        // when...
        String result = testSubject.sendAndWait(payload, String.class, testContext);
        // then...
        assertThat(result).isEqualTo(expectedResult);
        verify(mockCommandBus).dispatch(
                argThat(m -> {
                    Object resultPayload = m.payload();
                    return resultPayload != null && resultPayload.equals(payload);
                }),
                eq(testContext)
        );
    }

    @Test
    void sendAndWaitReturnsUnwrapExecutionException() {
        // given...
        when(mockCommandBus.dispatch(any(), any())).thenReturn(CompletableFuture.failedFuture(new MockException()));
        TestPayload payload = new TestPayload();
        // when/then...
        assertThatThrownBy(() -> testSubject.sendAndWait(payload, String.class)).isInstanceOf(MockException.class);
    }

    @Nested
    class SendWithMetadata {

        @Test
        void sendWithMetadataAttachesMetadataToDispatchedCommand() {
            // given
            TestPayload payload = new TestPayload();
            arrangeSuccessfulDispatch("OK");

            // when
            testSubject.send(payload, TEST_METADATA);

            // then
            assertDispatchedWithMetadata(payload, null);
        }

        @Test
        void sendWithMetadataAndContextAttachesMetadataAndPassesAlongContext() {
            // given
            TestPayload payload = new TestPayload();
            ProcessingContext testContext = new StubProcessingContext();
            arrangeSuccessfulDispatch("OK");

            // when
            testSubject.send(payload, TEST_METADATA, testContext);

            // then
            assertDispatchedWithMetadata(payload, testContext);
        }

        @Test
        void sendForResultTypeWithMetadataAttachesMetadataToDispatchedCommand() throws Exception {
            // given
            TestPayload payload = new TestPayload();
            arrangeSuccessfulDispatch("OK");

            // when
            CompletableFuture<String> result = testSubject.send(payload, String.class, TEST_METADATA, null);

            // then
            assertThat(result.get()).isEqualTo("OK");
            assertDispatchedWithMetadata(payload, null);
        }

        @Test
        void sendForResultTypeWithMetadataAndContextAttachesMetadataAndPassesAlongContext() throws Exception {
            // given
            TestPayload payload = new TestPayload();
            ProcessingContext testContext = new StubProcessingContext();
            arrangeSuccessfulDispatch("OK");

            // when
            CompletableFuture<String> result = testSubject.send(payload, String.class, TEST_METADATA, testContext);

            // then
            assertThat(result.get()).isEqualTo("OK");
            assertDispatchedWithMetadata(payload, testContext);
        }
    }

    @Nested
    class SendAndWaitWithMetadata {

        @Test
        void sendAndWaitWithMetadataAttachesMetadataAndReturnsExpectedResult() {
            // given
            TestPayload payload = new TestPayload();
            arrangeSuccessfulDispatch("OK");

            // when
            Object result = testSubject.sendAndWait(payload, Object.class, TEST_METADATA, null);

            // then
            assertThat(result).isEqualTo("OK");
            assertDispatchedWithMetadata(payload, null);
        }

        @Test
        void sendAndWaitWithMetadataAndContextAttachesMetadataAndPassesAlongContext() {
            // given
            TestPayload payload = new TestPayload();
            ProcessingContext testContext = new StubProcessingContext();
            arrangeSuccessfulDispatch("OK");

            // when
            Object result = testSubject.sendAndWait(payload, Object.class, TEST_METADATA, testContext);

            // then
            assertThat(result).isEqualTo("OK");
            assertDispatchedWithMetadata(payload, testContext);
        }

        @Test
        void sendAndWaitForResultTypeWithMetadataAttachesMetadataAndReturnsExpectedResult() {
            // given
            TestPayload payload = new TestPayload();
            arrangeSuccessfulDispatch("OK");

            // when
            String result = testSubject.sendAndWait(payload, String.class, TEST_METADATA, null);

            // then
            assertThat(result).isEqualTo("OK");
            assertDispatchedWithMetadata(payload, null);
        }

        @Test
        void sendAndWaitForResultTypeWithMetadataAndContextAttachesMetadataAndPassesAlongContext() {
            // given
            TestPayload payload = new TestPayload();
            ProcessingContext testContext = new StubProcessingContext();
            arrangeSuccessfulDispatch("OK");

            // when
            String result = testSubject.sendAndWait(payload, String.class, TEST_METADATA, testContext);

            // then
            assertThat(result).isEqualTo("OK");
            assertDispatchedWithMetadata(payload, testContext);
        }
    }

    private void arrangeSuccessfulDispatch(String expectedResult) {
        when(mockCommandBus.dispatch(any(), any())).thenAnswer(i -> CompletableFuture.completedFuture(
                new GenericCommandResultMessage(new MessageType("result"), expectedResult)
        ));
    }

    /**
     * Verifies a single command was dispatched carrying the given {@code payload} and the shared test metadata, with
     * the given {@code expectedContext} (or {@code null} for none).
     */
    private void assertDispatchedWithMetadata(TestPayload payload, @Nullable ProcessingContext expectedContext) {
        ArgumentCaptor<CommandMessage> commandCaptor = ArgumentCaptor.forClass(CommandMessage.class);
        verify(mockCommandBus).dispatch(commandCaptor.capture(),
                                        expectedContext == null ? isNull() : eq(expectedContext));
        CommandMessage dispatched = commandCaptor.getValue();
        assertThat(dispatched.payload()).isEqualTo(payload);
        assertThat(dispatched.metadata().get("key")).isEqualTo("value");
    }

    private static class TestPayload {

    }
}
