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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The different timeout components, {@link TimeoutWrappedMessageHandlingMember} and {@link TimeoutUnitOfWorkFactory}
 * are tested in isolation, but this test class combines them to ensure that the timeout behavior works as expected when
 * both are used together.
 */
class CombinedTimeoutTests {

    @AfterEach
    void tearDown() throws InterruptedException {
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        AxonTaskJanitor.INSTANCE.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    /**
     * Simple test where the message handling member takes longer than the timeout specified, and the unit of work
     * timeout is not reached.
     */
    @Test
    void onMessageHandlerInterruptWorks() {
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(100, () -> {
            Thread.sleep(200);
            return null;
        });
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(500);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
    }

    /**
     * Simple test where the unit of work timeout is shorter than the message handling member timeout, so the unit of
     * work timeout should kick in.
     */
    @Test
    void onUnitOfWorkInterruptWorks() {
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(500, () -> {
            Thread.sleep(200);
            return null;
        });
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(100);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
    }

    @Test
    void handlingMemberInterruptStillWorksIfExceptionIsWrapped() {
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(100, () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException("Wrapped exception", e);
            }
            return null;
        });
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(500);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
    }

    @Test
    void handlingMemberInterruptStillWorksIfExceptionIsIgnored() {
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(100, () -> {
            try {
                Thread.sleep(200);
            } catch (Exception e) {
                // Ignored
            }
            return null;
        });
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(500);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
    }

    @Test
    void unitOfWorkInterruptStillWorksIfExceptionIsWrapped() {
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(500, () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException("Wrapped exception", e);
            }
            return null;
        });
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(300);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
    }

    @Test
    void unitOfWorkInterruptStillWorksIfExceptionIsIgnored() {
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(500, () -> {
            try {
                Thread.sleep(200);
            } catch (Exception e) {
                // Ignored
            }
            return null;
        });
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(300);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        assertFalse(Thread.interrupted());
    }

    @Test
    void whenThreadIsInterruptedFromUnrelatedProcessTheInterruptIsPreserved() {
        TimeoutWrappedMessageHandlingMember<Object> mhm = createMessageHandlingMember(100000, () -> {
            Thread.sleep(20);
            Thread.currentThread().interrupt();
            return null;
        });
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(100000);

        CompletableFuture<?> result = doExecution(factory, mhm);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(InterruptedException.class, result.exceptionNow());
        assertTrue(Thread.interrupted());
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

    private TimeoutWrappedMessageHandlingMember<Object> createMessageHandlingMember(int timeout,
                                                                                    Callable<Object> callable) {
        return new TimeoutWrappedMessageHandlingMember<>(
                new SimpleMessageHandlingMember(callable),
                timeout,
                500,
                100
        );
    }

    private TimeoutUnitOfWorkFactory createTimeoutFactory(int timeout) {
        return new TimeoutUnitOfWorkFactory(
                UnitOfWorkTestUtils.SIMPLE_FACTORY,
                "TestComponent",
                timeout,
                500,
                100,
                AxonTaskJanitor.INSTANCE,
                AxonTaskJanitor.LOGGER
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
