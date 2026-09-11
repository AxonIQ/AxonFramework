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
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
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
    void timeoutClockStartsAtTheFirstPhaseActionNotAtCreation() throws InterruptedException {
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(150);
        UnitOfWork uow = factory.create(UUID.randomUUID().toString());
        // Simulates a gap between creation and the first phase action actually running
        Thread.sleep(200);

        EventHandler fastHandler = (event, context) -> MessageStream.empty();
        EventMessageHandlerInterceptorChain chain = new EventMessageHandlerInterceptorChain(List.of(), fastHandler);
        EventMessage event = EventTestUtils.asEventMessage("test");

        CompletableFuture<?> result =
                uow.executeWithResult(context -> chain.proceed(event, context).first().asCompletableFuture());

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

    @Test
    void surfacesTimeoutForASlowActionInTheAfterCommitPhase() {
        // The factory's own cleanup action also runs in an AFTER_COMMIT-adjacent phase; this proves it no longer
        // races ahead of a slow, concurrently-registered AFTER_COMMIT action and cancels the timeout prematurely.
        TimeoutUnitOfWorkFactory factory = createTimeoutFactory(100);
        UnitOfWork uow = factory.create(UUID.randomUUID().toString());
        uow.runOnAfterCommit(context -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                // Ignored, not re-interrupted
            }
        });

        CompletableFuture<Void> result = uow.execute();

        assertTrue(result.isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
    }

    @Test
    void interruptsTheActualWorkerThreadWhenPhaseActionsRunOnAsynchronousExecutor() {
        try (ExecutorService workScheduler = Executors.newSingleThreadExecutor()) {
            TimeoutUnitOfWorkFactory factory = new TimeoutUnitOfWorkFactory(
                    new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE, c -> c.workScheduler(workScheduler)),
                    "TestComponent",
                    100,
                    500,
                    10,
                    AxonTaskJanitor.INSTANCE,
                    AxonTaskJanitor.LOGGER
            );
            Thread creatorThread = Thread.currentThread();
            AtomicReference<Thread> workerThread = new AtomicReference<>();
            EventHandler sleepingHandler = (event, context) -> {
                workerThread.set(Thread.currentThread());
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return MessageStream.empty();
            };

            CompletableFuture<?> result = execute(factory, sleepingHandler);
            await().atMost(2, TimeUnit.SECONDS).until(result::isDone);

            assertTrue(result.isCompletedExceptionally());
            assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
            assertNotNull(workerThread.get());
            assertNotSame(creatorThread, workerThread.get(),
                          "The phase action must run on the configured workScheduler thread, not the creator thread");
            // Can't assert the worker thread's own flag: detectSwallowedInterruption clears it once converted into
            // the AxonTimeoutException above. Assert the creator thread instead, which must stay untouched.
            assertFalse(creatorThread.isInterrupted(),
                        "The creator thread must not be interrupted; only the actual worker thread should be");
        }
    }

    @Test
    void onlyTheStillActiveThreadIsInterruptedWhenMultiplePhaseActionsRunConcurrently() {
        try (ExecutorService workScheduler = Executors.newFixedThreadPool(2)) {
            TimeoutUnitOfWorkFactory factory = new TimeoutUnitOfWorkFactory(
                    new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE, c -> c.workScheduler(workScheduler)),
                    "TestComponent",
                    100,
                    500,
                    10,
                    AxonTaskJanitor.INSTANCE,
                    AxonTaskJanitor.LOGGER
            );
            UnitOfWork uow = factory.create(UUID.randomUUID().toString());
            AtomicBoolean slowActionInterrupted = new AtomicBoolean(false);
            AtomicBoolean fastActionInterrupted = new AtomicBoolean(false);
            AtomicReference<Thread> slowThread = new AtomicReference<>();
            AtomicReference<Thread> fastThread = new AtomicReference<>();

            // Two actions registered for the same phase run concurrently (UnitOfWork.runNextPhase()), each on its own
            // workScheduler thread. The fast one finishes and unbinds well before the timeout fires; the slow one is
            // still genuinely active when it does.
            uow.onPrepareCommit(context -> {
                slowThread.set(Thread.currentThread());
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    slowActionInterrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return FutureUtils.emptyCompletedFuture();
            });
            uow.onPrepareCommit(context -> {
                fastThread.set(Thread.currentThread());
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    fastActionInterrupted.set(true);
                }
                return FutureUtils.emptyCompletedFuture();
            });

            CompletableFuture<Void> result = uow.execute();
            await().atMost(2, TimeUnit.SECONDS).until(result::isDone);

            assertNotNull(slowThread.get());
            assertNotNull(fastThread.get());
            assertNotSame(slowThread.get(), fastThread.get(),
                          "Both phase actions must run concurrently, each on its own thread");
            assertTrue(result.isCompletedExceptionally());
            assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
            assertTrue(slowActionInterrupted.get(), "The still-active, genuinely slow action must be interrupted");
            assertFalse(fastActionInterrupted.get(), "The already-finished action must not be interrupted");
        }
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
