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

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventHandler;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.interception.EventMessageHandlerInterceptorChain;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link TimeoutUnitOfWorkFactory}.
 *
 * @author Steven van Beelen
 */
class TimeoutUnitOfWorkFactoryTest {

    @AfterEach
    void tearDown() throws InterruptedException {
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        AxonTaskJanitor.INSTANCE.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    @Test
    void interruptsUnitOfWorkWhenHandlingExceedsTimeout() {
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(100);
        EventHandler sleepingHandler = (event, context) -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return MessageStream.empty();
        };

        CompletableFuture<?> result = execute(factory, sleepingHandler);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
    }

    @Test
    void doesNotInterruptUnitOfWorkWhenHandlingCompletesInTime() {
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(500);
        EventHandler fastHandler = (event, context) -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return MessageStream.empty();
        };

        CompletableFuture<?> result = execute(factory, fastHandler);

        assertFalse(result.isCompletedExceptionally());
    }

    @Test
    void taskIsSharedAcrossMultipleInvocationsInSameUnitOfWork() {
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(150);
        EventHandler partialSleepHandler = (event, context) -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return MessageStream.empty();
        };
        EventMessageHandlerInterceptorChain chain =
                new EventMessageHandlerInterceptorChain(List.of(), partialSleepHandler);
        EventMessage first = EventTestUtils.asEventMessage("first");
        EventMessage second = EventTestUtils.asEventMessage("second");

        UnitOfWork uow = factory.create(UUID.randomUUID().toString());
        CompletableFuture<?> result = uow.executeWithResult(
                context -> chain.proceed(first, context)
                                .first()
                                .asCompletableFuture()
                                .thenCompose(ignored -> chain.proceed(second, context).first().asCompletableFuture())
        );

        // Neither invocation alone (100ms) exceeds the 150ms timeout, but their combined duration does, since the
        // factory attaches a single task to the UnitOfWork at creation, shared across every invocation within it.
        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
    }

    @Test
    void automaticallySurfacesTimeoutEvenWhenInvocationReportsSuccess() {
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(100);
        // Simulates a handler using the default LoggingErrorHandler-style behavior: it swallows the interruption
        // entirely (no re-interrupt) and reports success.
        EventHandler swallowingHandler = (event, context) -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                // Ignored, not re-interrupted
            }
            return MessageStream.empty();
        };

        CompletableFuture<?> result = execute(factory, swallowingHandler);

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
    }

    @Test
    void surfacesTimeoutForASwallowedInterruptionInThePrepareCommitPhase() {
        // Unlike the invocation phase, PREPARE_COMMIT was never covered by any ad hoc call site under the old
        // manual detectSwallowedInterruption mechanism; the installed interceptor now covers every phase uniformly.
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(100);
        UnitOfWork uow = factory.create(UUID.randomUUID().toString());
        uow.onPrepareCommit(context -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                // Ignored, not re-interrupted
            }
            return FutureUtils.emptyCompletedFuture();
        });

        CompletableFuture<Void> result = uow.execute();

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
    }

    private CompletableFuture<?> execute(TimeoutUnitOfWorkFactory factory, EventHandler terminalHandler) {
        EventMessageHandlerInterceptorChain chain =
                new EventMessageHandlerInterceptorChain(List.of(), terminalHandler);
        EventMessage event = EventTestUtils.asEventMessage("test");

        UnitOfWork uow = factory.create(UUID.randomUUID().toString());
        return uow.executeWithResult(context -> chain.proceed(event, context).first().asCompletableFuture());
    }

    private TimeoutUnitOfWorkFactory createTimeoutFactory(int timeout) {
        return new TimeoutUnitOfWorkFactory(
                UnitOfWorkTestUtils.SIMPLE_FACTORY,
                "TestComponent",
                timeout,
                500,
                10,
                AxonTaskJanitor.INSTANCE,
                AxonTaskJanitor.LOGGER
        );
    }
}
