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
package org.axonframework.messaging.core.timeout;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventHandler;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.interception.EventMessageHandlerInterceptorChain;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The different timeout components, {@link TimeoutWrappedMessageHandlingMember} and {@link TimeoutUnitOfWorkFactory}
 * are tested in isolation, but this test class combines them to ensure that the timeout behavior works as expected when
 * both are used together.
 */
class CombinedTimeoutTests {

    /**
     * Simple test where the message handling member takes longer than the timeout specified, and the unit of work
     * timeout is not reached.
     */
    @Test
    void onMessageHandlerInterruptWorks() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = AxonTaskJanitor.createExecutor();
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(100, () -> {
            Thread.sleep(200);
            return null;
        }, executor);
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(500, executor);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        executor.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    /**
     * Simple test where the unit of work timeout is shorter than the message handling member timeout, so the unit of
     * work timeout should kick in.
     */
    @Test
    void onUnitOfWorkInterruptWorks() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = AxonTaskJanitor.createExecutor();
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(500, () -> {
            Thread.sleep(200);
            return null;
        }, executor);
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(100, executor);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        executor.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    @Test
    void handlingMemberInterruptStillWorksIfExceptionIsWrapped() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = AxonTaskJanitor.createExecutor();
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(100, () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException("Wrapped exception", e);
            }
            return null;
        }, executor);
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(500, executor);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        executor.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    @Test
    void handlingMemberInterruptStillWorksIfExceptionIsIgnored() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = AxonTaskJanitor.createExecutor();
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(100, () -> {
            try {
                Thread.sleep(200);
            } catch (Exception e) {
                // Ignored
            }
            return null;
        }, executor);
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(500, executor);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        executor.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    @Test
    void unitOfWorkInterruptStillWorksIfExceptionIsWrapped() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = AxonTaskJanitor.createExecutor();
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(500, () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException("Wrapped exception", e);
            }
            return null;
        }, executor);
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(300, executor);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        executor.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    @Test
    void unitOfWorkInterruptStillWorksIfExceptionIsIgnored() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = AxonTaskJanitor.createExecutor();
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(500, () -> {
            try {
                Thread.sleep(200);
            } catch (Exception e) {
                // Ignored
            }
            return null;
        }, executor);
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(300, executor);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        executor.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    @Test
    void whenThreadIsInterruptedFromUnrelatedProcessTheInterruptIsPreserved() throws InterruptedException {
        ScheduledThreadPoolExecutor executor = AxonTaskJanitor.createExecutor();
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(100000, () -> {
            Thread.sleep(20);
            Thread.currentThread().interrupt();
            return null;
        }, executor);
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(100000, executor);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(InterruptedException.class, result.exceptionNow());
        assertTrue(Thread.interrupted());
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        executor.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    /**
     * Drives the given {@code mhm} through a {@link UnitOfWork} created by the given {@code timeoutFactory} for a batch
     * of two events, mirroring the original test's use of a two-message batch: the unit-of-work-level timeout is shared
     * across both messages, so scenarios where it is longer than a single handler invocation still time out once the
     * cumulative processing time of both messages exceeds it.
     */
    private CompletableFuture<?> doExecution(TimeoutUnitOfWorkFactory timeoutFactory,
                                             TimeoutWrappedMessageHandlingMember<Object> mhm) {
        EventHandler terminalHandler = (event, context) -> {
            MessageStream<?> result = mhm.handle(event, context, null);
            //noinspection unchecked,rawtypes
            return ((MessageStream) result).ignoreEntries();
        };
        EventMessageHandlerInterceptorChain chain = new EventMessageHandlerInterceptorChain(
                List.of(), terminalHandler
        );
        EventMessage first = EventTestUtils.asEventMessage("first");
        EventMessage second = EventTestUtils.asEventMessage("second");

        UnitOfWork uow = timeoutFactory.create(UUID.randomUUID().toString());
        return uow.executeWithResult(
                context -> chain.proceed(first, context)
                                .first()
                                .asCompletableFuture()
                                .thenCompose(ignored -> chain.proceed(second, context).first().asCompletableFuture())
        );
    }

    private TimeoutWrappedMessageHandlingMember<Object> createMessageHandlingMember(
            int timeout,
            Callable<Object> callable,
            ScheduledThreadPoolExecutor executor
    ) {
        return new TimeoutWrappedMessageHandlingMember<>(
                new SimpleMessageHandlingMember(callable), timeout, 500, 100, executor
        );
    }

    private TimeoutUnitOfWorkFactory createTimeoutFactory(
            int timeout,
            ScheduledThreadPoolExecutor executor
    ) {
        return new TimeoutUnitOfWorkFactory(
                UnitOfWorkTestUtils.SIMPLE_FACTORY, "TestComponent", timeout, 500, 100, executor
        );
    }

    private static class SimpleMessageHandlingMember implements MessageHandlingMember<Object> {

        private final Callable<Object> callable;

        private SimpleMessageHandlingMember(Callable<Object> callable) {
            this.callable = callable;
        }

        @NonNull
        @Override
        public Class<?> payloadType() {
            return String.class;
        }

        @Override
        public boolean canHandle(@NonNull Message message, @NonNull ProcessingContext context) {
            return true;
        }

        @Override
        public boolean canHandleMessageType(@NonNull Class<? extends Message> messageType) {
            return true;
        }

        @NonNull
        @Override
        public MessageStream<?> handle(@NonNull Message message,
                                       @NonNull ProcessingContext context,
                                       @Nullable Object target) {
            try {
                callable.call();
                return MessageStream.empty();
            } catch (Exception e) {
                return MessageStream.failed(e);
            }
        }

        @NonNull
        @Override
        public <HT> Optional<HT> unwrap(@NonNull Class<HT> handlerType) {
            return Optional.empty();
        }
    }
}
