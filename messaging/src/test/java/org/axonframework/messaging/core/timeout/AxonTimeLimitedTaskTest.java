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

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link AxonTimeLimitedTask}.
 *
 * @author Mitchell Herrijgers
 */
class AxonTimeLimitedTaskTest {

    @AfterEach
    void tearDown() throws InterruptedException {
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        AxonTaskJanitor.INSTANCE.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    @Test
    void correctlyInterruptsTaskWhenNoWarningWasConfiguredOnUncustomizedConstructor() {
        AxonTimeLimitedTask testSubject = new AxonTimeLimitedTask("My test task", 100, 100, 1);
        testSubject.bindToCurrentThread();

        assertThrows(InterruptedException.class, () -> {
            testSubject.start();
            // Even though the timeout is 100ms, the InterruptedException apparently needs time to travel up.
            // We accept a 150ms delay.
            Thread.sleep(150);
        });

        assertTrue(testSubject.isInterrupted());
        assertFalse(testSubject.isCompleted());
    }


    @Test
    void correctlyInterruptsTaskWithWarningWasConfiguredOnUncustomizedConstructor() {
        AxonTimeLimitedTask testSubject = new AxonTimeLimitedTask("My test task", 100, 50, 10);
        testSubject.bindToCurrentThread();

        assertThrows(InterruptedException.class, () -> {
            testSubject.start();
            // Even though the timeout is 100ms, the InterruptedException apparently needs time to travel up.
            // We accept a 150ms delay.
            Thread.sleep(150);
        });

        assertTrue(testSubject.isInterrupted());
        assertFalse(testSubject.isCompleted());
    }

    @Test
    void bindToCurrentThreadRedirectsTheScheduledInterruptToTheRebindThread() throws InterruptedException {
        AxonTimeLimitedTask testSubject = new AxonTimeLimitedTask("My test task", 200, 200, 1);
        AtomicBoolean workerWasInterrupted = new AtomicBoolean(false);
        CountDownLatch workerBound = new CountDownLatch(1);

        testSubject.start();
        Thread worker = new Thread(() -> {
            // Simulates a UnitOfWork phase action running on a different thread than the one that created the task.
            testSubject.bindToCurrentThread();
            workerBound.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                workerWasInterrupted.set(true);
            }
        });
        worker.start();

        assertTrue(workerBound.await(1, TimeUnit.SECONDS));
        worker.join(1000);

        assertTrue(workerWasInterrupted.get());
        assertFalse(Thread.currentThread().isInterrupted(),
                    "The creator (test) thread should not have been interrupted");
        assertTrue(testSubject.isInterrupted());
    }

    @Test
    void startIfNotStartedIsANoOpAfterTheTaskAlreadyStarted() {
        AxonTimeLimitedTask testSubject = new AxonTimeLimitedTask("My test task", 1000, 1000, 1);
        testSubject.bindToCurrentThread();

        testSubject.start();
        testSubject.startIfNotStarted();
        testSubject.startIfNotStarted();

        assertFalse(testSubject.isInterrupted());
        testSubject.complete();
    }

    @Test
    void startIfNotStartedStartsExactlyOnceUnderConcurrentCallers() throws InterruptedException {
        AxonTimeLimitedTask testSubject = new AxonTimeLimitedTask("My test task", 200, 200, 1);
        testSubject.bindToCurrentThread();
        int callerCount = 10;
        CountDownLatch readyLatch = new CountDownLatch(callerCount);
        CountDownLatch trigger = new CountDownLatch(1);
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < callerCount; i++) {
            Thread caller = new Thread(() -> {
                readyLatch.countDown();
                try {
                    trigger.await();
                    testSubject.startIfNotStarted();
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            callers.add(caller);
            caller.start();
        }

        assertTrue(readyLatch.await(1, TimeUnit.SECONDS));
        trigger.countDown();
        for (Thread caller : callers) {
            caller.join(1000);
        }

        assertTrue(errors.isEmpty(), "No concurrent caller of startIfNotStarted() should throw: " + errors);
        assertThrows(InterruptedException.class, () -> Thread.sleep(300));
        assertTrue(testSubject.isInterrupted());
    }

    @Test
    void correctlyLogsWarningsAndInterruptsWhenWarningWasConfiguredOnCustomizedConstructor() {
        Logger logger = spy(LoggerFactory.getLogger("MyLogger"));
        AxonTimeLimitedTask testSubject =
                new AxonTimeLimitedTask("My test task", 1000, 100, 100, AxonTaskJanitor.INSTANCE, logger);
        testSubject.bindToCurrentThread();

        assertThrows(InterruptedException.class, () -> {
            testSubject.start();
            // Even though the timeout is 100ms, the InterruptedException apparently needs time to travel up.
            // We accept a 1500ms delay.
            Thread.sleep(1500);
        });

        assertTrue(testSubject.isInterrupted());
        assertFalse(testSubject.isCompleted());
        verify(logger, atLeast(8)).warn(anyString(), any(), any(), any(), any());
    }

    @Test
    void doesNotInterruptButLogsWarningsIfProcessWasCompletedBeforeTimeout() throws InterruptedException {
        Logger logger = spy(LoggerFactory.getLogger("MyLogger"));
        AxonTimeLimitedTask testSubject =
                new AxonTimeLimitedTask("My test task", 1000, 100, 100, AxonTaskJanitor.INSTANCE, logger);
        testSubject.bindToCurrentThread();

        testSubject.start();
        // Even though the timeout is 100ms, the InterruptedException apparently needs time to travel up.
        // We accept a 500ms delay.
        Thread.sleep(500);

        assertFalse(testSubject.isInterrupted());
        assertFalse(testSubject.isCompleted());
        // Complete manually to ensure it does not block the AxonTaskJanitor!
        testSubject.complete();
        verify(logger, atLeast(3)).warn(anyString(), any(), any(), any(), any());
    }

    @Test
    void interruptsAllConcurrentlyActiveThreadsWhenTimeoutFires() throws InterruptedException {
        AxonTimeLimitedTask testSubject = new AxonTimeLimitedTask("My test task", 100, 100, 1);
        AtomicBoolean firstWorkerInterrupted = new AtomicBoolean(false);
        AtomicBoolean secondWorkerInterrupted = new AtomicBoolean(false);
        CountDownLatch bothBound = new CountDownLatch(2);

        testSubject.start();
        Thread firstWorker = new Thread(() -> {
            testSubject.bindToCurrentThread();
            bothBound.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                firstWorkerInterrupted.set(true);
            }
        });
        Thread secondWorker = new Thread(() -> {
            testSubject.bindToCurrentThread();
            bothBound.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                secondWorkerInterrupted.set(true);
            }
        });
        firstWorker.start();
        secondWorker.start();

        assertTrue(bothBound.await(1, TimeUnit.SECONDS));
        firstWorker.join(1000);
        secondWorker.join(1000);

        assertTrue(firstWorkerInterrupted.get(), "The first concurrently active thread must be interrupted");
        assertTrue(secondWorkerInterrupted.get(), "The second concurrently active thread must be interrupted");
    }

    @Test
    void unbindRemovesOnlyTheGivenThreadNotOthersStillActive() throws InterruptedException {
        AxonTimeLimitedTask testSubject = new AxonTimeLimitedTask("My test task", 200, 200, 1);
        AtomicBoolean finishedWorkerInterrupted = new AtomicBoolean(false);
        AtomicBoolean slowWorkerInterrupted = new AtomicBoolean(false);
        CountDownLatch bothBound = new CountDownLatch(2);

        testSubject.start();
        Thread finishedWorker = new Thread(() -> {
            testSubject.bindToCurrentThread();
            bothBound.countDown();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                finishedWorkerInterrupted.set(true);
            } finally {
                // Simulates the action completing (successfully) well before the timeout fires.
                testSubject.unbind(Thread.currentThread());
            }
        });
        Thread slowWorker = new Thread(() -> {
            testSubject.bindToCurrentThread();
            bothBound.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                slowWorkerInterrupted.set(true);
            }
        });
        finishedWorker.start();
        slowWorker.start();

        assertTrue(bothBound.await(1, TimeUnit.SECONDS));
        finishedWorker.join(1000);
        slowWorker.join(1000);

        assertFalse(finishedWorkerInterrupted.get(),
                    "The already-unbound thread must not be interrupted");
        assertTrue(slowWorkerInterrupted.get(), "The still-bound, still-active thread must be interrupted");
    }
}