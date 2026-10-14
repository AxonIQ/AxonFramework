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

package org.axonframework.messaging.core.unitofwork;

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.core.Context;
import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.axonframework.messaging.core.unitofwork.ProcessingLifecycle.DefaultPhases.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for implementations of the {@link ProcessingLifecycle}.
 *
 * @param <PL> The implementation of the {@link ProcessingLifecycle} being tested.
 * @author Allard Buijze
 * @author Gerard Klijs
 * @author Milan Savić
 * @author Sara Pellegrini
 * @author Steven van Beelen
 */
abstract class ProcessingLifecycleTest<PL extends ProcessingLifecycle> {

    /**
     * Constructs a test subject.
     *
     * @return A test subject.
     */
    abstract PL createTestSubject();

    /**
     * Execute the given {@code testSubject}, starting the test.
     *
     * @param testSubject The {@link ProcessingLifecycle} under test.
     * @return A {@link CompletableFuture} completing once the given {@code testSubject} executed.
     */
    abstract CompletableFuture<?> execute(PL testSubject);

    @Test
    void secondAttemptToCommitIsRejected() {
        PL testSubject = createTestSubject();
        execute(testSubject).join();

        assertThrows(Exception.class, () -> execute(testSubject));
    }

    @Test
    void synchronousActionsRegisteredInTheSamePhaseAlwaysCompleteBeforeEnteringTheSubsequentPhase() throws Exception {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        // Phase PreInvocation
        testSubject.onPreInvocation(fixture.createSyncHandler(ProcessingLifecycle.DefaultPhases.PRE_INVOCATION));
        testSubject.onPreInvocation(fixture.createSyncHandler(ProcessingLifecycle.DefaultPhases.PRE_INVOCATION));
        // Phase Invocation
        testSubject.onInvocation(fixture.createSyncHandler(INVOCATION));
        testSubject.onInvocation(fixture.createSyncHandler(INVOCATION));
        // Phase PostInvocation
        testSubject.onPostInvocation(fixture.createSyncHandler(POST_INVOCATION));
        testSubject.onPostInvocation(fixture.createSyncHandler(POST_INVOCATION));
        // Phase onPrepareCommit
        testSubject.onPrepareCommit(fixture.createSyncHandler(PREPARE_COMMIT));
        testSubject.onPrepareCommit(fixture.createSyncHandler(PREPARE_COMMIT));
        // Phase Commit
        testSubject.onCommit(fixture.createSyncHandler(COMMIT));
        testSubject.onCommit(fixture.createSyncHandler(COMMIT));
        // Phase AfterCommit
        testSubject.onAfterCommit(fixture.createSyncHandler(AFTER_COMMIT));
        testSubject.onAfterCommit(fixture.createSyncHandler(AFTER_COMMIT));

        execute(testSubject).get(1, TimeUnit.SECONDS);

        fixture.assertCompleteExecution();
    }

    @Test
    void synchronousActionsRegisteredInReversePhaseOrderAreExecutedInTheIntendedPhaseOrder() throws Exception {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        // Phase AfterCommit
        testSubject.onAfterCommit(fixture.createSyncHandler(AFTER_COMMIT));
        testSubject.onAfterCommit(fixture.createSyncHandler(AFTER_COMMIT));
        // Phase Commit
        testSubject.onCommit(fixture.createSyncHandler(COMMIT));
        testSubject.onCommit(fixture.createSyncHandler(COMMIT));
        // Phase onPrepareCommit
        testSubject.onPrepareCommit(fixture.createSyncHandler(PREPARE_COMMIT));
        testSubject.onPrepareCommit(fixture.createSyncHandler(PREPARE_COMMIT));
        // Phase PostInvocation
        testSubject.onPostInvocation(fixture.createSyncHandler(POST_INVOCATION));
        testSubject.onPostInvocation(fixture.createSyncHandler(POST_INVOCATION));
        // Phase Invocation
        testSubject.onInvocation(fixture.createSyncHandler(INVOCATION));
        testSubject.onInvocation(fixture.createSyncHandler(INVOCATION));
        // Phase PreInvocation
        testSubject.onPreInvocation(fixture.createSyncHandler(PRE_INVOCATION));
        testSubject.onPreInvocation(fixture.createSyncHandler(PRE_INVOCATION));

        execute(testSubject).get(1, TimeUnit.SECONDS);

        fixture.assertCompleteExecution();
    }

    @Test
    void asynchronousActionsRegisteredInTheSamePhaseAlwaysCompleteBeforeEnteringTheSubsequentPhase() throws Exception {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        // Phase PreInvocation
        testSubject.onPreInvocation(fixture.createAsyncHandler(PRE_INVOCATION));
        testSubject.onPreInvocation(fixture.createAsyncHandler(PRE_INVOCATION));
        // Phase Invocation
        testSubject.onInvocation(fixture.createAsyncHandler(INVOCATION));
        testSubject.onInvocation(fixture.createAsyncHandler(INVOCATION));
        // Phase PostInvocation
        testSubject.onPostInvocation(fixture.createAsyncHandler(POST_INVOCATION));
        testSubject.onPostInvocation(fixture.createAsyncHandler(POST_INVOCATION));
        // Phase onPrepareCommit
        testSubject.onPrepareCommit(fixture.createAsyncHandler(PREPARE_COMMIT));
        testSubject.onPrepareCommit(fixture.createAsyncHandler(PREPARE_COMMIT));
        // Phase Commit
        testSubject.onCommit(fixture.createAsyncHandler(COMMIT));
        testSubject.onCommit(fixture.createAsyncHandler(COMMIT));
        // Phase AfterCommit
        testSubject.onAfterCommit(fixture.createAsyncHandler(AFTER_COMMIT));
        testSubject.onAfterCommit(fixture.createAsyncHandler(AFTER_COMMIT));

        execute(testSubject).get(1, TimeUnit.SECONDS);

        fixture.assertCompleteExecution();
    }

    @Test
    void asynchronousActionsRegisteredInReversePhaseOrderAreExecutedInTheIntendedPhaseOrder() throws Exception {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        // Phase AfterCommit
        testSubject.onAfterCommit(fixture.createAsyncHandler(AFTER_COMMIT));
        testSubject.onAfterCommit(fixture.createAsyncHandler(AFTER_COMMIT));
        // Phase Commit
        testSubject.onCommit(fixture.createAsyncHandler(COMMIT));
        testSubject.onCommit(fixture.createAsyncHandler(COMMIT));
        // Phase onPrepareCommit
        testSubject.onPrepareCommit(fixture.createAsyncHandler(PREPARE_COMMIT));
        testSubject.onPrepareCommit(fixture.createAsyncHandler(PREPARE_COMMIT));
        // Phase PostInvocation
        testSubject.onPostInvocation(fixture.createAsyncHandler(POST_INVOCATION));
        testSubject.onPostInvocation(fixture.createAsyncHandler(POST_INVOCATION));
        // Phase Invocation
        testSubject.onInvocation(fixture.createAsyncHandler(INVOCATION));
        testSubject.onInvocation(fixture.createAsyncHandler(INVOCATION));
        // Phase PreInvocation
        testSubject.onPreInvocation(fixture.createAsyncHandler(PRE_INVOCATION));
        testSubject.onPreInvocation(fixture.createAsyncHandler(PRE_INVOCATION));

        execute(testSubject).get(1, TimeUnit.SECONDS);

        fixture.assertCompleteExecution();
    }

    @Test
    void asynchronousActionsRegisteredInReversePhaseOrderWithDifferingTimeoutsAreExecutedInTheIntendedPhaseOrder()
            throws Exception {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        // Phase AfterCommit
        testSubject.onAfterCommit(fixture.createAsyncHandler(AFTER_COMMIT, 10));
        testSubject.onAfterCommit(fixture.createAsyncHandler(AFTER_COMMIT, 10));
        // Phase Commit
        testSubject.onCommit(fixture.createAsyncHandler(COMMIT, 15));
        testSubject.onCommit(fixture.createAsyncHandler(COMMIT, 15));
        // Phase onPrepareCommit
        testSubject.onPrepareCommit(fixture.createAsyncHandler(PREPARE_COMMIT, 20));
        testSubject.onPrepareCommit(fixture.createAsyncHandler(PREPARE_COMMIT, 20));
        // Phase PostInvocation
        testSubject.onPostInvocation(fixture.createAsyncHandler(POST_INVOCATION, 25));
        testSubject.onPostInvocation(fixture.createAsyncHandler(POST_INVOCATION, 25));
        // Phase Invocation
        testSubject.onInvocation(fixture.createAsyncHandler(INVOCATION, 30));
        testSubject.onInvocation(fixture.createAsyncHandler(INVOCATION, 30));
        // Phase PreInvocation
        testSubject.onPreInvocation(fixture.createAsyncHandler(PRE_INVOCATION, 35));
        testSubject.onPreInvocation(fixture.createAsyncHandler(PRE_INVOCATION, 35));

        execute(testSubject).get(1, TimeUnit.SECONDS);

        fixture.assertCompleteExecution();
    }

    @Test
    void asynchronousActionsRegisteredByShufflingThePhasesAreExecutedInTheIntendedPhaseOrder() throws Exception {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        Random random = ThreadLocalRandom.current();
        List<ProcessingLifecycle.Phase> phases = Arrays.stream(values()).collect(Collectors.toList());
        Collections.shuffle(phases, random);
        for (ProcessingLifecycle.Phase phase : phases) {
            testSubject.on(phase, fixture.createAsyncHandler(phase, random.nextInt(100)));
            testSubject.on(phase, fixture.createAsyncHandler(phase, random.nextInt(100)));
        }

        execute(testSubject).get(1, TimeUnit.SECONDS);

        fixture.assertCompleteExecution();
    }

    @Test
    void rollbackRegisteredActionsAreNotInvokedWhenEverythingSucceeds() throws Exception {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();
        AtomicBoolean invoked = new AtomicBoolean(false);

        testSubject.onError((context, phase, e) -> invoked.set(true));

        execute(testSubject).get(1, TimeUnit.SECONDS);

        fixture.assertCompleteExecution();
        assertFalse(invoked.get());
    }

    @Test
    void errorHandlersAreInvokedWhenAnActionFailsInInvocationPhase() {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();
        AtomicBoolean onErrorInvoked = new AtomicBoolean(false);
        AtomicBoolean whenCompleteInvoked = new AtomicBoolean(false);
        AtomicBoolean doFinallyInvoked = new AtomicBoolean(false);

        testSubject.onInvocation(fixture.createExceptionThrower(INVOCATION))
                   .onError((context, phase, e) -> onErrorInvoked.set(true))
                   .whenComplete(context -> whenCompleteInvoked.set(true))
                   .doFinally(context -> doFinallyInvoked.set(true));

        CompletableFuture<?> result = execute(testSubject);
        assertTrue(result.isCompletedExceptionally());

        fixture.assertCompleteExecution();
        fixture.assertErrorHappeningInPhase(INVOCATION);
        assertTrue(onErrorInvoked.get());
        assertFalse(whenCompleteInvoked.get());
        assertTrue(doFinallyInvoked.get());
    }

    @Test
    void errorHandlersAreInvokedWhenAnActionFailsInCommitPhase() {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();
        AtomicBoolean onErrorInvoked = new AtomicBoolean(false);
        AtomicBoolean whenCompleteInvoked = new AtomicBoolean(false);
        AtomicBoolean doFinallyInvoked = new AtomicBoolean(false);

        testSubject.onInvocation(fixture.createExceptionThrower(COMMIT));
        testSubject.onError((context, phase, e) -> onErrorInvoked.set(true));
        testSubject.whenComplete(context -> whenCompleteInvoked.set(true));
        testSubject.doFinally(context -> doFinallyInvoked.set(true));

        CompletableFuture<?> result = execute(testSubject);
        assertTrue(result.isCompletedExceptionally());

        fixture.assertCompleteExecution();
        fixture.assertErrorHappeningInPhase(COMMIT);
        assertTrue(onErrorInvoked.get());
        assertFalse(whenCompleteInvoked.get());
        assertTrue(doFinallyInvoked.get());
    }

    @Test
    void multipleExceptionsInSamePhaseAreSuppressedByFirstException() {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();
        testSubject.onInvocation(fixture.createExceptionThrower(INVOCATION, new RuntimeException("First")));
        testSubject.onInvocation(fixture.createExceptionThrower(INVOCATION, new RuntimeException("Second")));
        testSubject.onInvocation(fixture.createExceptionThrower(INVOCATION, new RuntimeException("Third")));

        CompletableFuture<?> result = execute(testSubject);
        assertTrue(result.isCompletedExceptionally());

        try {
            result.get();
            fail("Expected exception");
        } catch (ExecutionException e) {
            assertEquals("First", e.getCause().getMessage());
            assertEquals(2, e.getCause().getSuppressed().length);
        } catch (InterruptedException e) {
            fail("Wrong exception");
        }
    }

    @Test
    void phasesOccurringAfterTheFailingPhaseAreNotExecuted() {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        testSubject.onInvocation(fixture.createExceptionThrower(INVOCATION));
        testSubject.onPostInvocation(fixture.createSyncHandler(POST_INVOCATION));
        testSubject.onPrepareCommit(fixture.createAsyncHandler(PREPARE_COMMIT));
        testSubject.onCommit(fixture.createSyncHandler(COMMIT));
        testSubject.onAfterCommit(fixture.createAsyncHandler(AFTER_COMMIT));

        CompletableFuture<?> result = execute(testSubject);
        assertTrue(result.isDone());
        assertTrue(result.isCompletedExceptionally());

        fixture.assertErrorHappeningInPhase(INVOCATION);
        fixture.assertNotInvoked(p -> p.order() > INVOCATION.order());
    }

    @Test
    void customPhasesAreExecutedRespectingOrder() {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        ProcessingLifecycle.Phase customPhase = () -> Integer.MIN_VALUE + 1;
        testSubject.on(customPhase, fixture.createSyncHandler(customPhase));
        testSubject.onInvocation(fixture.createSyncHandler(INVOCATION));
        testSubject.onPostInvocation(fixture.createSyncHandler(POST_INVOCATION));
        testSubject.onPrepareCommit(fixture.createSyncHandler(PREPARE_COMMIT));
        testSubject.onCommit(fixture.createSyncHandler(COMMIT));
        testSubject.onAfterCommit(fixture.createSyncHandler(AFTER_COMMIT));

        CompletableFuture<?> result = execute(testSubject);
        assertFalse(result.isCompletedExceptionally());
        assertTrue(result.isDone());

        assertEquals(1, fixture.countExecuted(phase -> phase == customPhase));
    }

    @Test
    void handlersRegisteredDuringExecutionOfTheFirstPhaseAreExecuted() {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        testSubject.onInvocation(fixture.createSyncHandler(INVOCATION).andThen(f -> {
            testSubject.onPostInvocation(fixture.createSyncHandler(POST_INVOCATION));
            testSubject.onPrepareCommit(fixture.createSyncHandler(PREPARE_COMMIT));
            testSubject.onCommit(fixture.createSyncHandler(COMMIT));
            testSubject.onAfterCommit(fixture.createSyncHandler(AFTER_COMMIT));
            return f;
        }));

        CompletableFuture<?> result = execute(testSubject);
        assertTrue(result.isDone());
        assertEquals(1, fixture.countExecuted(phase -> phase == COMMIT));
    }

    @Test
    void handlersRegisteredDuringExecutionOfAnEarlierPhaseAreExecuted() {
        PL testSubject = createTestSubject();
        LinkedList<CompletableFuture<Void>> futures = new LinkedList<>();
        AtomicBoolean invoked = new AtomicBoolean(false);
        testSubject.onPreInvocation(c -> {
                                        c.onInvocation(c1 -> {
                                            c1.onCommit(c2 -> {
                                                c2.onAfterCommit(c3 -> {
                                                    invoked.set(true);
                                                    CompletableFuture<Void> e = new CompletableFuture<>();
                                                    futures.add(e);
                                                    return e;
                                                });
                                                CompletableFuture<Void> e = new CompletableFuture<>();
                                                futures.add(e);
                                                return e;
                                            });
                                            CompletableFuture<Void> e = new CompletableFuture<>();
                                            futures.add(e);
                                            return e;
                                        });
                                        CompletableFuture<Void> e = new CompletableFuture<>();
                                        futures.add(e);
                                        return e;
                                    }
        );

        CompletableFuture<?> result = execute(testSubject);
        assertFalse(result.isDone());
        while (!futures.isEmpty()) {
            futures.poll().complete(null);
        }
        assertTrue(result.isDone());
        assertTrue(invoked.get());
    }

    /**
     * A handler cannot register for the phase it is running in, but the {@link ProcessingLifecycle.Phase} interface
     * takes an arbitrary {@link ProcessingLifecycle.Phase#order()}, so the gaps between the
     * {@link ProcessingLifecycle.DefaultPhases} are available to a handler that needs work done after its own phase
     * without waiting for the next default one.
     */
    @Test
    void handlersRegisteredDuringAPhaseForACustomLaterPhaseRunBetweenTheSurroundingDefaultPhases() {
        PL testSubject = createTestSubject();
        ProcessingLifecycle.Phase betweenPrepareCommitAndCommit = () -> PREPARE_COMMIT.order() + 1;
        List<ProcessingLifecycle.Phase> order = new CopyOnWriteArrayList<>();

        testSubject.runOnPrepareCommit(c -> {
            order.add(PREPARE_COMMIT);
            c.runOn(betweenPrepareCommitAndCommit, c1 -> order.add(betweenPrepareCommitAndCommit));
        });
        testSubject.runOnCommit(c -> order.add(COMMIT));

        CompletableFuture<?> result = execute(testSubject);

        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
        assertEquals(List.of(PREPARE_COMMIT, betweenPrepareCommitAndCommit, COMMIT), order);
    }

    @Test
    void registeringHandlersInTheRunningPhaseCausesHandlerToFail() {
        PL testSubject = createTestSubject();

        testSubject.onPrepareCommit(ctx -> {
            ctx.onPrepareCommit(c2 -> FutureUtils.emptyCompletedFuture());
            return FutureUtils.emptyCompletedFuture();
        });

        CompletableFuture<?> actual = execute(testSubject);
        ExecutionException actualException = assertThrows(ExecutionException.class, actual::get);
        assertTrue(actualException.getMessage().contains("already in phase PREPARE_COMMIT"));
    }

    @Test
    void registeringHandlersInPastPhasesCausesHandlerToFail() {
        PL testSubject = createTestSubject();

        testSubject.onInvocation(ctx -> {
            ctx.onPreInvocation(c2 -> FutureUtils.emptyCompletedFuture());
            return FutureUtils.emptyCompletedFuture();
        });

        CompletableFuture<?> actual = execute(testSubject);
        ExecutionException actualException = assertThrows(ExecutionException.class, actual::get);
        assertTrue(actualException.getMessage().contains("ProcessingContext is already in phase INVOCATION"));
    }

    @Test
    void completionHandlersAreInvokedAtWhenProcessingContextCompletes() {
        PL testSubject = createTestSubject();
        AtomicBoolean invoked = new AtomicBoolean();
        testSubject.whenComplete(pc -> invoked.set(true));

        CompletableFuture<?> actual = execute(testSubject);
        assertTrue(actual.isDone());
        assertTrue(invoked.get());
    }

    @Test
    void exceptionsInCompletionHandlersAreLoggedAndSuppressed() {
        PL testSubject = createTestSubject();

        AtomicBoolean invoked = new AtomicBoolean();
        testSubject.whenComplete(pc -> {
            invoked.set(true);
            throw new MockException("Mocking failure");
        });

        CompletableFuture<?> actual = execute(testSubject);
        assertTrue(actual.isDone());
        assertTrue(invoked.get());
    }

    @Test
    void exceptionsInErrorHandlersAreLoggedAndSuppressed() {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();
        testSubject.onInvocation(fixture.createExceptionThrower(INVOCATION));


        AtomicBoolean invoked = new AtomicBoolean();
        testSubject.onError((pc, ph, e) -> {
            invoked.set(true);
            throw new MockException("Mocking failure");
        });

        CompletableFuture<?> actual = execute(testSubject);
        assertTrue(actual.isDone());
        assertTrue(invoked.get());
    }

    @Test
    void completionHandlersAreInvokedImmediatelyWhenProcessingContextIsAlreadyCompleted() {
        PL testSubject = createTestSubject();
        CompletableFuture<?> actual = execute(testSubject);

        assertTrue(actual.isDone());
        AtomicBoolean invoked = new AtomicBoolean();
        testSubject.whenComplete(pc -> invoked.set(true));
        assertTrue(invoked.get());
    }

    @Test
    void completionHandlersAreNotInvokedWhenProcessingContextIsCompletedWithError() {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();
        testSubject.onInvocation(fixture.createExceptionThrower(INVOCATION));

        CompletableFuture<?> actual = execute(testSubject);

        assertTrue(actual.isDone());
        AtomicBoolean invoked = new AtomicBoolean();
        testSubject.whenComplete(pc -> invoked.set(true));
        assertFalse(invoked.get());
    }

    @Test
    void errorHandlersAreInvokedImmediatelyWhenProcessingContextIsAlreadyCompletedWithError() {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        testSubject.onInvocation(fixture.createExceptionThrower(INVOCATION));

        CompletableFuture<?> actual = execute(testSubject);
        assertTrue(actual.isDone());
        fixture.assertErrorHappeningInPhase(INVOCATION);

        AtomicBoolean invoked = new AtomicBoolean();
        testSubject.onError((pc, phase, e) -> invoked.set(true));
        assertTrue(invoked.get());

        assertTrue(testSubject.isError());
        assertTrue(testSubject.isCompleted());
        assertFalse(testSubject.isCommitted());
        assertTrue(testSubject.isStarted());
    }

    @Test
    void lifecycleStatusIsStartedInLifecycleHandlers() {
        PL testSubject = createTestSubject();

        assertFalse(testSubject.isStarted());
        assertFalse(testSubject.isCommitted());
        assertFalse(testSubject.isCompleted());
        assertFalse(testSubject.isError());

        testSubject.runOnInvocation(pc -> {
            assertFalse(testSubject.isError());
            assertFalse(testSubject.isCompleted());
            assertFalse(testSubject.isCommitted());
            assertTrue(testSubject.isStarted());
        });

        execute(testSubject).join();

        assertFalse(testSubject.isError());
        assertTrue(testSubject.isCompleted());
        assertTrue(testSubject.isCommitted());
        assertTrue(testSubject.isStarted());
    }

    @Test
    void errorHandlersAreNotInvokedWhenProcessingContextIsCompleted() {
        PL testSubject = createTestSubject();

        CompletableFuture<?> actual = execute(testSubject);
        assertTrue(actual.isDone());

        AtomicBoolean invoked = new AtomicBoolean();
        testSubject.onError((pc, phase, e) -> invoked.set(true));
        assertFalse(invoked.get());
    }

    @Test
    void finallyHandlersAreInvokedImmediatelyWhenProcessingContextIsAlreadyCompleted() {
        PL testSubject = createTestSubject();
        CompletableFuture<?> actual = execute(testSubject);

        assertTrue(actual.isDone());
        AtomicBoolean invoked = new AtomicBoolean();
        testSubject.doFinally(pc -> invoked.set(true));
        assertTrue(invoked.get());
    }

    @Test
    void finallyHandlersAreInvokedImmediatelyWhenProcessingContextIsAlreadyCompletedWithError() {
        PL testSubject = createTestSubject();
        ProcessingLifecycleFixture fixture = new ProcessingLifecycleFixture();

        testSubject.onInvocation(fixture.createExceptionThrower(INVOCATION));

        CompletableFuture<?> actual = execute(testSubject);
        assertTrue(actual.isDone());
        fixture.assertErrorHappeningInPhase(INVOCATION);

        AtomicBoolean invoked = new AtomicBoolean();
        testSubject.doFinally(pc -> invoked.set(true));
        assertTrue(invoked.get());
    }

    @Test
    void resourceRegisteredInOnePhaseAreAccessibleInAnother() {
        PL testSubject = createTestSubject();
        Context.ResourceKey<String> testKey = Context.ResourceKey.withLabel("testKey");

        testSubject.runOnInvocation(pc -> pc.putResource(testKey, "testValue"));
        testSubject.runOnPostInvocation(pc -> assertEquals("testValue", pc.getResource(testKey)));

        execute(testSubject).join();
    }

    @Test
    void putIfAbsentIgnoredWhenValueExists() {
        PL testSubject = createTestSubject();
        Context.ResourceKey<String> testKey = Context.ResourceKey.withLabel("testKey");

        testSubject.runOnInvocation(pc -> pc.putResource(testKey, "testValue"));
        testSubject.runOnPostInvocation(pc -> pc.putResourceIfAbsent(testKey, "anotherTestValue"));
        testSubject.runOnAfterCommit(pc -> assertEquals("testValue", pc.getResource(testKey)));

        execute(testSubject).join();
    }

    @Test
    void putIfAbsentStoresValue() {
        PL testSubject = createTestSubject();
        Context.ResourceKey<String> testKey = Context.ResourceKey.withLabel("testKey");

        testSubject.runOnInvocation(pc -> assertNull(pc.putResourceIfAbsent(testKey, "testValue")));
        testSubject.runOnPostInvocation(pc -> assertEquals("testValue", pc.getResource(testKey)));

        execute(testSubject).join();
    }

    @Test
    void computeIfAbsentIgnoredWhenValueExists() {
        PL testSubject = createTestSubject();
        Context.ResourceKey<String> testKey = Context.ResourceKey.withLabel("testKey");

        testSubject.runOnInvocation(pc -> pc.putResource(testKey, "testValue"));
        testSubject.runOnPostInvocation(pc -> assertEquals("testValue",
                                                           pc.computeResourceIfAbsent(testKey,
                                                                                      () -> Assertions.fail(
                                                                                              "Should not have invoked supplier"))));
        testSubject.runOnAfterCommit(pc -> assertEquals("testValue", pc.getResource(testKey)));

        execute(testSubject).join();
    }

    @Test
    void computeIfAbsentStoresValue() {
        PL testSubject = createTestSubject();
        Context.ResourceKey<String> testKey = Context.ResourceKey.withLabel("testKey");

        testSubject.runOnInvocation(pc -> assertEquals("testValue",
                                                       pc.computeResourceIfAbsent(testKey, () -> "testValue")));
        testSubject.runOnPostInvocation(pc -> assertEquals("testValue", pc.getResource(testKey)));

        execute(testSubject).join();
    }

    @Test
    void updateResourceStoresUpdatedResource() {
        PL testSubject = createTestSubject();
        Context.ResourceKey<String> testKey = Context.ResourceKey.withLabel("testKey");

        testSubject.runOnPreInvocation(pc -> pc.putResource(testKey, "testValue"));
        testSubject.runOnInvocation(pc -> assertEquals("testValue2", pc.updateResource(testKey, s -> s + "2")));
        testSubject.runOnPostInvocation(pc -> assertEquals("testValue2", pc.getResource(testKey)));

        execute(testSubject).join();
    }

    @Test
    void putResourceOverwritesExisting() {
        PL testSubject = createTestSubject();
        Context.ResourceKey<String> testKey = Context.ResourceKey.withLabel("testKey");

        testSubject.runOnInvocation(pc -> pc.putResource(testKey, "testValue1"));
        testSubject.runOnPostInvocation(pc -> assertEquals("testValue1", pc.putResource(testKey, "testValue2")));
        testSubject.runOnAfterCommit(pc -> pc.putResource(testKey, "anotherTestValue"));
        testSubject.runOnAfterCommit(pc -> assertEquals("anotherTestValue", pc.getResource(testKey)));

        execute(testSubject).join();
    }

    @Test
    void removeResource() {
        PL testSubject = createTestSubject();
        Context.ResourceKey<String> testKey = Context.ResourceKey.withLabel("testKey");

        testSubject.runOnPreInvocation(pc -> pc.putResource(testKey, "testValue"));
        testSubject.runOnInvocation(pc -> assertEquals("testValue", pc.removeResource(testKey)));
        testSubject.runOnPostInvocation(pc -> assertFalse(pc.containsResource(testKey)));
        testSubject.runOnPostInvocation(pc -> assertNull(pc.getResource(testKey)));

        execute(testSubject).join();
    }

    @Test
    void removeResourceIgnoresWhenCurrentResourceDoesNotMatch() {
        PL testSubject = createTestSubject();
        Context.ResourceKey<String> testKey = Context.ResourceKey.withLabel("testKey");

        testSubject.runOnPreInvocation(pc -> pc.putResource(testKey, "testValue"));
        testSubject.runOnInvocation(pc -> assertFalse(pc.removeResource(testKey, "anotherValue")));
        testSubject.runOnAfterCommit(pc -> assertTrue(pc.containsResource(testKey)));
        testSubject.runOnAfterCommit(pc -> assertEquals("testValue", pc.getResource(testKey)));

        execute(testSubject).join();
    }

    @Test
    void removeResourceWhenCurrentResourceMatches() {
        PL testSubject = createTestSubject();
        Context.ResourceKey<String> testKey = Context.ResourceKey.withLabel("testKey");

        testSubject.runOnPreInvocation(pc -> pc.putResource(testKey, "testValue"));
        testSubject.runOnInvocation(pc -> assertTrue(pc.removeResource(testKey, "testValue")));
        testSubject.runOnAfterCommit(pc -> assertFalse(pc.containsResource(testKey)));
        testSubject.runOnPostInvocation(pc -> assertNull(pc.getResource(testKey)));

        execute(testSubject).join();
    }

    /**
     * Test fixture intended for validating the invocation of actions registered in
     * {@link ProcessingLifecycle.Phase phases} within the
     * {@link ProcessingLifecycle}.
     */
    private static class ProcessingLifecycleFixture {

        private final Map<ProcessingLifecycle.Phase, Integer> phaseToHandlerCount = new ConcurrentHashMap<>();
        private final List<ExecutionCompleted> completedExecutions = new CopyOnWriteArrayList<>();

        private SyncHandler createSyncHandler(ProcessingLifecycle.Phase phase) {
            incrementCounter(phase);
            return new SyncHandler(phase, completedExecutions);
        }

        private AsyncHandler createAsyncHandler(ProcessingLifecycle.Phase phase) {
            return createAsyncHandler(phase, 100);
        }

        private AsyncHandler createAsyncHandler(ProcessingLifecycle.Phase phase, int sleepMilliseconds) {
            incrementCounter(phase);
            return new AsyncHandler(phase, completedExecutions, sleepMilliseconds);
        }

        private ExceptionThrower createExceptionThrower(ProcessingLifecycle.Phase phase) {
            return createExceptionThrower(phase, new IllegalStateException("Some exception"));
        }

        private ExceptionThrower createExceptionThrower(ProcessingLifecycle.Phase phase, Throwable throwable) {
            incrementCounter(phase);
            return new ExceptionThrower(throwable, phase, completedExecutions);
        }

        private void incrementCounter(ProcessingLifecycle.Phase phase) {
            phaseToHandlerCount.compute(phase, (p, currentCount) -> currentCount == null ? 1 : currentCount + 1);
        }

        public void assertCompleteExecution() {
            long handlerCount = filteredHandlerCount(entry -> true);
            assertAmountOfHandlers(handlerCount);
            assertAmountOfExecutedHandlers(handlerCount);
            assertExecutionOrder();
        }

        public void assertErrorHappeningInPhase(ProcessingLifecycle.Phase phase) {
            // assert everything before and during given phase is invoked
            assertInvoked(p -> !p.isAfter(phase));

            // assert everything after give phase is not invoked
            assertNotInvoked(p -> p.isAfter(phase));
        }

        private void assertInvoked(Predicate<ProcessingLifecycle.Phase> phaseFilter) {
            int expected = phaseToHandlerCount.entrySet()
                                              .stream()
                                              .filter(ks -> phaseFilter.test(ks.getKey()))
                                              .mapToInt(Map.Entry::getValue)
                                              .sum();
            assertEquals(expected, countExecuted(phaseFilter));
        }

        private void assertNotInvoked(Predicate<ProcessingLifecycle.Phase> phaseFilter) {
            assertEquals(0, countExecuted(phaseFilter));
        }

        private int countExecuted(Predicate<ProcessingLifecycle.Phase> phaseFilter) {
            return (int) completedExecutions.stream()
                                            .filter(executionCompleted -> phaseFilter.test(executionCompleted.phase()))
                                            .count();
        }

        private void assertAmountOfHandlers(long nonRollbackHandlerCount) {
            assertEquals(nonRollbackHandlerCount, completedExecutions.size());
        }

        private long filteredHandlerCount(Predicate<Map.Entry<ProcessingLifecycle.Phase, Integer>> phaseFilter) {
            return phaseToHandlerCount.entrySet()
                                      .stream()
                                      .filter(phaseFilter)
                                      .map(Map.Entry::getValue)
                                      .reduce(Integer::sum)
                                      .orElse(0);
        }

        private void assertAmountOfExecutedHandlers(long nonRollbackHandlerCount) {
            Set<String> ids = completedExecutions.stream()
                                                 .map(ExecutionCompleted::id)
                                                 .collect(Collectors.toSet());
            assertEquals(nonRollbackHandlerCount, ids.size());
        }

        private void assertExecutionOrder() {
            int phaseOrder = Integer.MIN_VALUE;
            for (ExecutionCompleted executionCompleted : completedExecutions) {
                assertTrue(executionCompleted.phase.order() >= phaseOrder,
                           "Phase [" + executionCompleted.phase + "] (" + executionCompleted.phase.order()
                                   + ") was executed out of order, as it executed after another phase with order "
                                   + phaseOrder);
                phaseOrder = executionCompleted.phase.order();
            }
        }
    }

    private record ExecutionCompleted(
            String id,
            ProcessingLifecycle.Phase phase
    ) {

    }

    private static class SyncHandler extends Handler {

        private SyncHandler(ProcessingLifecycle.Phase phase, List<ExecutionCompleted> completedExecutions) {
            super(phase, completedExecutions);
        }

        @Override
        public CompletableFuture<?> apply(ProcessingContext processingContext) {
            completedExecutions.add(new ExecutionCompleted(id, phase));
            return FutureUtils.emptyCompletedFuture();
        }
    }

    private static class AsyncHandler extends Handler {

        private final int sleepMs;

        private AsyncHandler(ProcessingLifecycle.Phase phase,
                             List<ExecutionCompleted> completedExecutions,
                             int sleepMs) {
            super(phase, completedExecutions);
            this.sleepMs = sleepMs;
        }

        @Override
        public CompletableFuture<?> apply(ProcessingContext processingContext) {
            return CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(sleepMs);
                    completedExecutions.add(new ExecutionCompleted(id, phase));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static class ExceptionThrower extends Handler {

        private final Throwable throwable;

        public ExceptionThrower(Throwable throwable,
                                ProcessingLifecycle.Phase phase,
                                List<ExecutionCompleted> completedExecutions) {
            super(phase, completedExecutions);
            this.throwable = throwable;
        }

        @Override
        public CompletableFuture<?> apply(ProcessingContext processingContext) {
            completedExecutions.add(new ExecutionCompleted(id, phase));
            return CompletableFuture.failedFuture(throwable);
        }
    }

    private static abstract class Handler implements Function<ProcessingContext, CompletableFuture<?>> {

        final String id = UUID.randomUUID().toString();
        final ProcessingLifecycle.Phase phase;
        final List<ExecutionCompleted> completedExecutions;

        private Handler(ProcessingLifecycle.Phase phase, List<ExecutionCompleted> completedExecutions) {
            this.phase = phase;
            this.completedExecutions = completedExecutions;
        }
    }
}




