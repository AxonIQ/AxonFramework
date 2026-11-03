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

package org.axonframework.messaging.commandhandling.interception;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandPriorityCalculator;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.commandhandling.annotation.AnnotationRoutingStrategy;
import org.axonframework.messaging.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.messaging.core.DelayedMessageStream;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.*;
import org.mockito.junit.jupiter.*;
import org.mockito.stubbing.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link CommandSequencingInterceptor}.
 *
 * @author Jakob Hatzl
 */
@ExtendWith(MockitoExtension.class)
class CommandSequencingInterceptorTest {

    private static final CommandMessage TEST_MESSAGE_1 = new GenericCommandMessage(new MessageType("message"),
                                                                                   "payload");
    private static final CommandMessage TEST_MESSAGE_2 = new GenericCommandMessage(new MessageType("message"),
                                                                                   "payload");
    @Mock
    private SequencingPolicy<CommandMessage> sequencingPolicy;
    @Mock
    private MessageHandlerInterceptorChain<CommandMessage> interceptorChain1;
    @Mock
    private ProcessingContext ctx1;
    @Mock
    private Message exCmd1Result;
    @Captor
    private ArgumentCaptor<Consumer<ProcessingContext>> ctx1CompletionCapture;
    @Mock
    private MessageHandlerInterceptorChain<CommandMessage> interceptorChain2;
    @Mock
    private ProcessingContext ctx2;
    @Mock
    private Message exCmd2Result;
    @Captor
    private ArgumentCaptor<Consumer<ProcessingContext>> ctx2CompletionCapture;

    private CommandSequencingInterceptor<CommandMessage> testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new CommandSequencingInterceptor<>(sequencingPolicy);
    }

    @Test
    void interceptOnHandleSerializesCommandExecutionForSameSequence() throws ExecutionException, InterruptedException {
        Object exSequenceIdentifier = "sequenceIdentifier";
        ExecutorService executor = Executors.newFixedThreadPool(2);

        when(sequencingPolicy.sequenceIdentifierFor(any(), any()))
                .thenReturn(Optional.of(exSequenceIdentifier));
        when(interceptorChain1.proceed(any(), any()))
                .thenAnswer((Answer<MessageStream<? extends Message>>) invocation -> MessageStream.just(exCmd1Result));
        when(interceptorChain2.proceed(any(), any()))
                .thenAnswer((Answer<MessageStream<? extends Message>>) invocation -> MessageStream.just(exCmd2Result));

        // handle command 1
        Future<? extends MessageStream<?>> ctx1Execution =
                executor.submit(() -> testSubject.interceptOnHandle(TEST_MESSAGE_1, ctx1, interceptorChain1));

        // verify command 1 returns immediately with the result message stream not wrapped in DelayedMessageStream
        await().untilAsserted(() -> assertEquals(Future.State.SUCCESS, ctx1Execution.state()));
        MessageStream<?> cmd1ResultStream = ctx1Execution.get();
        assertFalse(cmd1ResultStream instanceof DelayedMessageStream<?>);
        assertTrue(cmd1ResultStream.hasNextAvailable());
        assertEquals(exCmd1Result, cmd1ResultStream.next().orElseThrow().message());
        // verify sequencing policy is called and capture the context completion callback
        verify(sequencingPolicy)
                .sequenceIdentifierFor(TEST_MESSAGE_1, ctx1);
        verify(ctx1)
                .doFinally(ctx1CompletionCapture.capture());
        verify(interceptorChain1)
                .proceed(TEST_MESSAGE_1, ctx1);

        // handle command 2 before releasing ctx1 lock by invoking the context completion callback
        Future<? extends MessageStream<?>> ctx2Execution =
                executor.submit(() -> testSubject.interceptOnHandle(TEST_MESSAGE_2, ctx2, interceptorChain2));

        // verify command 2 returns immediately, but with the result message stream wrapped in DelayedMessageStream
        // verify the wrapped command 2 result message stream does not have the result message available yet
        await().untilAsserted(() -> assertEquals(Future.State.SUCCESS, ctx2Execution.state()));
        MessageStream<?> cmd2ResultStream = ctx2Execution.get();
        assertInstanceOf(DelayedMessageStream.class, cmd2ResultStream);
        await()
                .pollDelay(Duration.ofSeconds(1))
                .untilAsserted(() -> assertFalse(cmd2ResultStream.hasNextAvailable()));

        // release lock of ctx1
        ctx1CompletionCapture.getValue().accept(ctx1);

        // verify the wrapped command 2 result message stream has the result available now after releasing the lock
        await()
                .untilAsserted(() -> assertTrue(cmd2ResultStream.hasNextAvailable()));
        assertEquals(exCmd2Result, cmd2ResultStream.next().orElseThrow().message());
        // verify sequencing policy is called and capture the context completion callback
        verify(sequencingPolicy)
                .sequenceIdentifierFor(TEST_MESSAGE_2, ctx2);
        verify(ctx2)
                .doFinally(ctx2CompletionCapture.capture());
        verify(interceptorChain2)
                .proceed(TEST_MESSAGE_2, ctx2);

        // release lock of ctx2
        ctx2CompletionCapture.getValue().accept(ctx2);

        verifyNoMoreInteractions(sequencingPolicy, interceptorChain1, interceptorChain2, ctx1, ctx2);

        executor.shutdown();
    }

    @Test
    void interceptOnHandleDoesNotSerializeCommandExecutionForDifferentSequence()
            throws ExecutionException, InterruptedException {
        Object exSequenceIdentifier1 = "sequenceIdentifier1";
        Object exSequenceIdentifier2 = "sequenceIdentifier2";
        ExecutorService executor = Executors.newFixedThreadPool(2);

        when(sequencingPolicy.sequenceIdentifierFor(TEST_MESSAGE_1, ctx1))
                .thenReturn(Optional.of(exSequenceIdentifier1));
        when(sequencingPolicy.sequenceIdentifierFor(TEST_MESSAGE_2, ctx2))
                .thenReturn(Optional.of(exSequenceIdentifier2));
        when(interceptorChain1.proceed(any(), any()))
                .thenAnswer((Answer<MessageStream<? extends Message>>) invocation -> MessageStream.just(exCmd1Result));
        when(interceptorChain2.proceed(any(), any()))
                .thenAnswer((Answer<MessageStream<? extends Message>>) invocation -> MessageStream.just(exCmd2Result));

        // handle command 1
        Future<? extends MessageStream<?>> ctx1Execution =
                executor.submit(() -> testSubject.interceptOnHandle(TEST_MESSAGE_1, ctx1, interceptorChain1));

        // verify command 1 returns immediately with the result message stream not wrapped in DelayedMessageStream
        await().untilAsserted(() -> assertEquals(Future.State.SUCCESS, ctx1Execution.state()));
        MessageStream<?> cmd1ResultStream = ctx1Execution.get();
        assertFalse(cmd1ResultStream instanceof DelayedMessageStream<?>);
        assertTrue(cmd1ResultStream.hasNextAvailable());
        assertEquals(exCmd1Result, cmd1ResultStream.next().orElseThrow().message());
        // verify sequencing policy is called and capture the context completion callback
        verify(sequencingPolicy)
                .sequenceIdentifierFor(TEST_MESSAGE_1, ctx1);
        verify(ctx1)
                .doFinally(ctx1CompletionCapture.capture());
        verify(interceptorChain1)
                .proceed(TEST_MESSAGE_1, ctx1);

        // handle command 2
        Future<? extends MessageStream<?>> ctx2Execution =
                executor.submit(() -> testSubject.interceptOnHandle(TEST_MESSAGE_2, ctx2, interceptorChain2));

        // verify command 2 returns immediately with the result message stream not wrapped in DelayedMessageStream, despite the lock on ctx1 not yet released
        await().untilAsserted(() -> assertEquals(Future.State.SUCCESS, ctx2Execution.state()));
        MessageStream<?> cmd2ResultStream = ctx2Execution.get();
        assertFalse(cmd2ResultStream instanceof DelayedMessageStream<?>);
        assertTrue(cmd2ResultStream.hasNextAvailable());
        assertEquals(exCmd2Result, cmd2ResultStream.next().orElseThrow().message());
        // verify sequencing policy is called and capture the context completion callback
        verify(sequencingPolicy)
                .sequenceIdentifierFor(TEST_MESSAGE_2, ctx2);
        verify(ctx2)
                .doFinally(ctx2CompletionCapture.capture());
        verify(interceptorChain2)
                .proceed(TEST_MESSAGE_2, ctx2);

        // release lock of ctx1
        ctx1CompletionCapture.getValue().accept(ctx1);
        // release lock of ctx2
        ctx2CompletionCapture.getValue().accept(ctx2);

        verifyNoMoreInteractions(sequencingPolicy, interceptorChain1, interceptorChain2, ctx1, ctx2);

        executor.shutdown();
    }

    @Test
    void interceptOnHandleSkipsSerializationForEmptyIdentifier() {
        ProcessingContext testContext = new StubProcessingContext();
        MessageHandlerInterceptorChain<CommandMessage> handlerInterceptorChain = mock();

        when(sequencingPolicy.sequenceIdentifierFor(any(), any()))
                .thenReturn(Optional.empty());

        testSubject.interceptOnHandle(TEST_MESSAGE_1, testContext, handlerInterceptorChain);

        verify(sequencingPolicy).sequenceIdentifierFor(TEST_MESSAGE_1, testContext);
        verify(handlerInterceptorChain)
                .proceed(TEST_MESSAGE_1, testContext);
        verifyNoMoreInteractions(handlerInterceptorChain, sequencingPolicy);
    }

    @Test
    void interceptOnHandleForwardsLockToNestedCommandWithCorrelationIdWithoutDeadlock() {
        Object sequenceIdentifier = "sequenceIdentifier";
        when(sequencingPolicy.sequenceIdentifierFor(any(), any()))
                .thenReturn(Optional.of(sequenceIdentifier));

        CommandMessage parentMessage = new GenericCommandMessage(new MessageType("parent"), "payload");
        CommandMessage childMessage = new GenericCommandMessage(
                new MessageType("child"),
                "payload",
                Metadata.with("correlationId", parentMessage.identifier())
        );

        when(interceptorChain1.proceed(eq(parentMessage), any()))
                .thenAnswer(invocation -> {
                    // Simulate synchronous nested command execution with correlationId pointing to parent
                    MessageStream<?> nestedStream = testSubject.interceptOnHandle(childMessage, ctx2, interceptorChain2);
                    assertFalse(nestedStream instanceof DelayedMessageStream<?>);
                    return MessageStream.just(exCmd1Result);
                });
        doReturn(MessageStream.just(exCmd2Result))
                .when(interceptorChain2).proceed(eq(childMessage), any());

        MessageStream<?> resultStream = testSubject.interceptOnHandle(parentMessage, ctx1, interceptorChain1);

        assertNotNull(resultStream);
        verify(interceptorChain1).proceed(parentMessage, ctx1);
        verify(interceptorChain2).proceed(childMessage, ctx2);
    }

    @Test
    void interceptOnHandleForwardsLockToNestedCommandWithCausationIdWithoutDeadlock() {
        Object sequenceIdentifier = "sequenceIdentifier";
        when(sequencingPolicy.sequenceIdentifierFor(any(), any()))
                .thenReturn(Optional.of(sequenceIdentifier));

        CommandMessage parentMessage = new GenericCommandMessage(new MessageType("parent"), "payload");
        CommandMessage childMessage = new GenericCommandMessage(
                new MessageType("child"),
                "payload",
                Metadata.with("causationId", parentMessage.identifier())
        );

        when(interceptorChain1.proceed(eq(parentMessage), any()))
                .thenAnswer(invocation -> {
                    MessageStream<?> nestedStream = testSubject.interceptOnHandle(childMessage, ctx2, interceptorChain2);
                    assertFalse(nestedStream instanceof DelayedMessageStream<?>);
                    return MessageStream.just(exCmd1Result);
                });
        doReturn(MessageStream.just(exCmd2Result))
                .when(interceptorChain2).proceed(eq(childMessage), any());

        MessageStream<?> resultStream = testSubject.interceptOnHandle(parentMessage, ctx1, interceptorChain1);

        assertNotNull(resultStream);
        verify(interceptorChain1).proceed(parentMessage, ctx1);
        verify(interceptorChain2).proceed(childMessage, ctx2);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void interceptOnHandleForwardsLockAcrossThreadsWhenNested() throws Exception {
        Object sequenceIdentifier = "sequenceIdentifier";
        when(sequencingPolicy.sequenceIdentifierFor(any(), any()))
                .thenReturn(Optional.of(sequenceIdentifier));

        CommandMessage parentMessage = new GenericCommandMessage(new MessageType("parent"), "payload");
        CommandMessage childMessage = new GenericCommandMessage(
                new MessageType("child"),
                "payload",
                Metadata.with("correlationId", parentMessage.identifier())
        );

        when(interceptorChain1.proceed(eq(parentMessage), any()))
                .thenAnswer(invocation -> {
                    ExecutorService childExecutor = Executors.newSingleThreadExecutor();
                    try {
                        MessageStream<?> childStream = childExecutor.submit(() ->
                                testSubject.interceptOnHandle(childMessage, ctx2, interceptorChain2)
                        ).get();
                        assertFalse(childStream instanceof DelayedMessageStream<?>);
                    } finally {
                        childExecutor.shutdownNow();
                    }
                    return MessageStream.just(exCmd1Result);
                });
        doReturn(MessageStream.just(exCmd2Result))
                .when(interceptorChain2).proceed(eq(childMessage), any());

        MessageStream<?> resultStream = testSubject.interceptOnHandle(parentMessage, ctx1, interceptorChain1);

        assertNotNull(resultStream);
        verify(interceptorChain1).proceed(parentMessage, ctx1);
        verify(interceptorChain2).proceed(childMessage, ctx2);
    }

    @Test
    void interceptOnHandleSupportsFourLevelsOfNesting() {
        Object sequenceIdentifier = "sequenceIdentifier";
        when(sequencingPolicy.sequenceIdentifierFor(any(), any()))
                .thenReturn(Optional.of(sequenceIdentifier));

        CommandMessage cmd1 = new GenericCommandMessage(new MessageType("cmd1"), "payload1");
        CommandMessage cmd2 = new GenericCommandMessage(
                new MessageType("cmd2"),
                "payload2",
                Metadata.with("correlationId", cmd1.identifier())
        );
        CommandMessage cmd3 = new GenericCommandMessage(
                new MessageType("cmd3"),
                "payload3",
                Metadata.with("causationId", cmd2.identifier()).and("correlationId", cmd1.identifier())
        );
        CommandMessage cmd4 = new GenericCommandMessage(
                new MessageType("cmd4"),
                "payload4",
                Metadata.with("causationId", cmd3.identifier()).and("correlationId", cmd1.identifier())
        );

        ProcessingContext ctx3 = mock();
        ProcessingContext ctx4 = mock();
        MessageHandlerInterceptorChain<CommandMessage> chain3 = mock();
        MessageHandlerInterceptorChain<CommandMessage> chain4 = mock();
        Message res3 = mock();
        Message res4 = mock();

        when(interceptorChain1.proceed(eq(cmd1), any()))
                .thenAnswer(inv1 -> {
                    MessageStream<?> s2 = testSubject.interceptOnHandle(cmd2, ctx2, interceptorChain2);
                    assertFalse(s2 instanceof DelayedMessageStream<?>);
                    return MessageStream.just(exCmd1Result);
                });
        when(interceptorChain2.proceed(eq(cmd2), any()))
                .thenAnswer(inv2 -> {
                    MessageStream<?> s3 = testSubject.interceptOnHandle(cmd3, ctx3, chain3);
                    assertFalse(s3 instanceof DelayedMessageStream<?>);
                    return MessageStream.just(exCmd2Result);
                });
        when(chain3.proceed(eq(cmd3), any()))
                .thenAnswer(inv3 -> {
                    MessageStream<?> s4 = testSubject.interceptOnHandle(cmd4, ctx4, chain4);
                    assertFalse(s4 instanceof DelayedMessageStream<?>);
                    return MessageStream.just(res3);
                });
        doReturn(MessageStream.just(res4))
                .when(chain4).proceed(eq(cmd4), any());

        MessageStream<?> result = testSubject.interceptOnHandle(cmd1, ctx1, interceptorChain1);

        assertNotNull(result);
        verify(interceptorChain1).proceed(cmd1, ctx1);
        verify(interceptorChain2).proceed(cmd2, ctx2);
        verify(chain3).proceed(cmd3, ctx3);
        verify(chain4).proceed(cmd4, ctx4);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void interceptOnHandleUnwindsNestedStackAndReleasesLockToUnrelatedCommandOnlyWhenRootCompletes() {
        Object sequenceIdentifier = "sequenceIdentifier";
        when(sequencingPolicy.sequenceIdentifierFor(any(), any()))
                .thenReturn(Optional.of(sequenceIdentifier));

        CommandMessage cmdA = new GenericCommandMessage(new MessageType("cmdA"), "payloadA");
        CommandMessage cmdB = new GenericCommandMessage(
                new MessageType("cmdB"),
                "payloadB",
                Metadata.with("correlationId", cmdA.identifier())
        );
        CommandMessage cmdC = new GenericCommandMessage(
                new MessageType("cmdC"),
                "payloadC",
                Metadata.with("causationId", cmdB.identifier()).and("correlationId", cmdA.identifier())
        );
        CommandMessage cmdUnrelated = new GenericCommandMessage(new MessageType("cmdUnrelated"), "payloadUnrelated");

        ProcessingContext ctxA = mock();
        ProcessingContext ctxB = mock();
        ProcessingContext ctxC = mock();
        ProcessingContext ctxUnrelated = mock();

        MessageHandlerInterceptorChain<CommandMessage> chainA = mock();
        MessageHandlerInterceptorChain<CommandMessage> chainB = mock();
        MessageHandlerInterceptorChain<CommandMessage> chainC = mock();
        MessageHandlerInterceptorChain<CommandMessage> chainUnrelated = mock();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ProcessingContext>> captorA = ArgumentCaptor.forClass(Consumer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ProcessingContext>> captorB = ArgumentCaptor.forClass(Consumer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ProcessingContext>> captorC = ArgumentCaptor.forClass(Consumer.class);

        doReturn(MessageStream.just(mock())).when(chainA).proceed(eq(cmdA), any());
        doReturn(MessageStream.just(mock())).when(chainB).proceed(eq(cmdB), any());
        doReturn(MessageStream.just(mock())).when(chainC).proceed(eq(cmdC), any());
        Message unrelatedResult = mock();
        doReturn(MessageStream.just(unrelatedResult)).when(chainUnrelated).proceed(eq(cmdUnrelated), any());

        // 1. Starts A
        MessageStream<?> streamA = testSubject.interceptOnHandle(cmdA, ctxA, chainA);
        assertFalse(streamA instanceof DelayedMessageStream<?>);
        verify(ctxA).doFinally(captorA.capture());

        // 2. Starts nested B
        MessageStream<?> streamB = testSubject.interceptOnHandle(cmdB, ctxB, chainB);
        assertFalse(streamB instanceof DelayedMessageStream<?>);
        verify(ctxB).doFinally(captorB.capture());

        // 3. Starts nested C
        MessageStream<?> streamC = testSubject.interceptOnHandle(cmdC, ctxC, chainC);
        assertFalse(streamC instanceof DelayedMessageStream<?>);
        verify(ctxC).doFinally(captorC.capture());

        // Queue an unrelated command while nested C is active
        MessageStream<?> streamUnrelated = testSubject.interceptOnHandle(cmdUnrelated, ctxUnrelated, chainUnrelated);
        assertInstanceOf(DelayedMessageStream.class, streamUnrelated);
        assertFalse(streamUnrelated.hasNextAvailable());

        // 4. Completes C
        captorC.getValue().accept(ctxC);
        assertFalse(streamUnrelated.hasNextAvailable());
        verify(chainUnrelated, never()).proceed(eq(cmdUnrelated), any());

        // 5. Completes B
        captorB.getValue().accept(ctxB);

        // 6. Verifies that A still owns the lock
        assertFalse(streamUnrelated.hasNextAvailable());
        verify(chainUnrelated, never()).proceed(eq(cmdUnrelated), any());

        // 7. Completes A
        captorA.getValue().accept(ctxA);

        // 8. Verifies that an unrelated command can now execute
        await().untilAsserted(() -> assertTrue(streamUnrelated.hasNextAvailable()));
        assertEquals(unrelatedResult, streamUnrelated.next().orElseThrow().message());
        verify(chainUnrelated).proceed(eq(cmdUnrelated), eq(ctxUnrelated));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void commandGatewaySendAndWaitExecutesNestedCommandWithoutDeadlock() {
        SimpleCommandBus simpleCommandBus = new SimpleCommandBus(
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
        );
        CommandSequencingInterceptor<CommandMessage> sequencingInterceptor =
                new CommandSequencingInterceptor<>((msg, ctx) -> Optional.of("aggregate-123"));

        InterceptingCommandBus interceptingBus = new InterceptingCommandBus(
                simpleCommandBus,
                List.of(sequencingInterceptor),
                List.of()
        );

        DefaultCommandGateway gateway = new DefaultCommandGateway(
                interceptingBus,
                payloadType -> Optional.of(new MessageType(payloadType)),
                CommandPriorityCalculator.defaultCalculator(),
                new AnnotationRoutingStrategy()
        );

        QualifiedName parentName = new QualifiedName(ParentPayload.class);
        QualifiedName childName = new QualifiedName(ChildPayload.class);

        interceptingBus.subscribe(childName, (command, context) ->
                MessageStream.just(new GenericCommandResultMessage(new MessageType("Result"), "child-ok"))
        );

        interceptingBus.subscribe(parentName, (command, context) -> {
            String childResult = gateway.sendAndWait(
                    new ChildPayload(),
                    String.class,
                    Metadata.with("correlationId", command.identifier()),
                    context
            );
            return MessageStream.just(new GenericCommandResultMessage(new MessageType("Result"), "parent:" + childResult));
        });

        String result = gateway.sendAndWait(new ParentPayload(), String.class);
        assertEquals("parent:child-ok", result);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void commandGatewaySendWithJoinExecutesNestedCommandWithoutDeadlock() {
        SimpleCommandBus simpleCommandBus = new SimpleCommandBus(
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
        );
        CommandSequencingInterceptor<CommandMessage> sequencingInterceptor =
                new CommandSequencingInterceptor<>((msg, ctx) -> Optional.of("aggregate-123"));

        InterceptingCommandBus interceptingBus = new InterceptingCommandBus(
                simpleCommandBus,
                List.of(sequencingInterceptor),
                List.of()
        );

        DefaultCommandGateway gateway = new DefaultCommandGateway(
                interceptingBus,
                payloadType -> Optional.of(new MessageType(payloadType)),
                CommandPriorityCalculator.defaultCalculator(),
                new AnnotationRoutingStrategy()
        );

        QualifiedName parentName = new QualifiedName(ParentPayload.class);
        QualifiedName childName = new QualifiedName(ChildPayload.class);

        ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch allowChildCompletion = new CountDownLatch(1);
        try {
            interceptingBus.subscribe(childName, (command, context) ->
                    MessageStream.fromFuture(CompletableFuture.supplyAsync(() -> {
                        try {
                            allowChildCompletion.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return new GenericCommandResultMessage(new MessageType("Result"), "child-ok");
                    }, asyncExecutor))
            );

            interceptingBus.subscribe(parentName, (command, context) -> {
                CompletableFuture<String> childFuture = gateway.send(
                        new ChildPayload(),
                        String.class,
                        Metadata.with("correlationId", command.identifier()),
                        context
                );
                assertFalse(childFuture.isDone());
                allowChildCompletion.countDown();
                String childResult = childFuture.join();
                return MessageStream.just(new GenericCommandResultMessage(new MessageType("Result"), "parent:" + childResult));
            });

            String result = gateway.sendAndWait(new ParentPayload(), String.class);
            assertEquals("parent:child-ok", result);
        } finally {
            allowChildCompletion.countDown();
            asyncExecutor.shutdownNow();
        }
    }

    private record ParentPayload() {}
    private record ChildPayload() {}
}
