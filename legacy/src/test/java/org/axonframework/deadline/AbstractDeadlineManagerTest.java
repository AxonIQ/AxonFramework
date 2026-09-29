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

package org.axonframework.deadline;

import org.axonframework.messaging.core.ContextAwareScope;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating {@link AbstractDeadlineManager#runOnPrepareCommitOrNow(Runnable)} and
 * {@link AbstractDeadlineManager#processDispatchInterceptors(DeadlineMessage)}, driven through a recording subclass
 * shaped like the Axon Framework 4 {@code SimpleDeadlineManager}: it creates the message and schedule id up front and
 * defers the interception and the actual call.
 */
class AbstractDeadlineManagerTest {

    private static final ScopeDescriptor EXPLICIT_SCOPE = () -> "explicitScope";

    private List<String> timeline;
    private RecordingDeadlineManager testSubject;

    @BeforeEach
    void setUp() {
        timeline = new CopyOnWriteArrayList<>();
        testSubject = new RecordingDeadlineManager("manager", timeline);
    }

    @Nested
    class WithoutAnActiveScope {

        @Test
        void scheduleRunsImmediately() {
            // when
            String scheduleId = testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE);

            // then
            assertThat(testSubject.scheduled).singleElement()
                                             .satisfies(call -> {
                                                 assertThat(call.scheduleId()).isEqualTo(scheduleId);
                                                 assertThat(call.scope()).isSameAs(EXPLICIT_SCOPE);
                                             });
        }

        @Test
        void cancelCallsRunImmediately() {
            // when
            testSubject.cancelSchedule("deadlineName", "scheduleId");
            testSubject.cancelAll("deadlineName");
            testSubject.cancelAllWithinScope("deadlineName", EXPLICIT_SCOPE);

            // then
            assertThat(timeline).containsExactly("manager:cancelSchedule deadlineName/scheduleId",
                                                 "manager:cancelAll deadlineName",
                                                 "manager:cancelAllWithinScope deadlineName@explicitScope");
        }

        /**
         * Axon Framework 4 threw here as well: the scope-less overloads ask for the current scope, and there is none.
         * Failing is what keeps a deadline from being stored under a scope nothing can resolve.
         */
        @Test
        void scheduleWithoutAScopeDescriptorThrows() {
            // when / then
            assertThatThrownBy(() -> testSubject.schedule(Instant.now(), "deadlineName", "payload"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot request current Scope if none is active");
            assertThat(testSubject.scheduled).isEmpty();
        }

        @Test
        void cancelAllWithinScopeWithoutAScopeDescriptorThrows() {
            // when / then
            assertThatThrownBy(() -> testSubject.cancelAllWithinScope("deadlineName"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot request current Scope if none is active");
            assertThat(timeline).isEmpty();
        }

        /**
         * Axon Framework 4 deferred whenever a unit of work was active. Axon Framework 5 has no ambient one, so
         * without a scope carrying the context there is nothing to defer to, even while a context is running.
         */
        @Test
        void aCallMadeWhileAContextRunsButNoScopeIsActiveRunsImmediately() {
            // given
            AtomicInteger scheduledDuringInvocation = new AtomicInteger(-1);

            // when
            runInUnitOfWork(context -> {
                testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE);
                scheduledDuringInvocation.set(testSubject.scheduled.size());
            });

            // then
            assertThat(scheduledDuringInvocation).hasValue(1);
        }
    }

    @Nested
    class WithinAContextAwareScope {

        @Test
        void scheduleIsDeferredUntilTheContextPreparesItsCommit() {
            // given
            AtomicInteger scheduledDuringInvocation = new AtomicInteger(-1);

            // when
            runInUnitOfWork(context -> new TestScope(context).run(() -> {
                testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE);
                scheduledDuringInvocation.set(testSubject.scheduled.size());
            }));

            // then
            assertThat(scheduledDuringInvocation).hasValue(0);
            assertThat(testSubject.scheduled).hasSize(1);
        }

        @Test
        void cancelCallsAreDeferredUntilTheContextPreparesItsCommit() {
            // given
            AtomicInteger callsDuringInvocation = new AtomicInteger(-1);

            // when
            runInUnitOfWork(context -> new TestScope(context).run(() -> {
                testSubject.cancelSchedule("deadlineName", "scheduleId");
                testSubject.cancelAll("deadlineName");
                testSubject.cancelAllWithinScope("deadlineName");
                callsDuringInvocation.set(timeline.size());
            }));

            // then
            assertThat(callsDuringInvocation).hasValue(0);
            assertThat(timeline).containsExactly("manager:cancelSchedule deadlineName/scheduleId",
                                                 "manager:cancelAll deadlineName",
                                                 "manager:cancelAllWithinScope deadlineName@testScope");
        }

        @Test
        void scheduleWithoutAScopeDescriptorUsesTheDescriptorOfTheCurrentScope() {
            // when
            runInUnitOfWork(context -> new TestScope(context).run(
                    () -> testSubject.schedule(Instant.now(), "deadlineName", "payload")
            ));

            // then
            assertThat(testSubject.scheduled).singleElement()
                                             .extracting(ScheduledCall::scope)
                                             .extracting(ScopeDescriptor::scopeDescription)
                                             .isEqualTo("testScope");
        }

        /**
         * Inherited from Axon Framework 4: the schedule id is known when the call is made and returned right away,
         * although the call itself only runs once the context prepares its commit.
         */
        @Test
        void theScheduleIdIsReturnedBeforeTheDeferredCallRuns() {
            // given
            AtomicReference<String> returnedId = new AtomicReference<>();

            // when
            runInUnitOfWork(context -> new TestScope(context).run(() -> returnedId.set(
                    testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE)
            )));

            // then
            assertThat(returnedId.get()).isNotNull();
            assertThat(testSubject.scheduled).singleElement()
                                             .extracting(ScheduledCall::scheduleId)
                                             .isEqualTo(returnedId.get());
        }

        @Test
        void deferredCallsNeverRunWhenTheContextRollsBack() {
            // given
            UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();

            // when
            CompletableFuture<Object> result = unitOfWork.executeWithResult(context -> {
                new TestScope(context).run(() -> {
                    testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE);
                    testSubject.cancelAll("deadlineName");
                });
                return CompletableFuture.failedFuture(new IllegalStateException("handler failure"));
            });

            // then
            assertThatThrownBy(() -> result.orTimeout(1, TimeUnit.SECONDS).join())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(testSubject.scheduled).isEmpty();
            assertThat(timeline).isEmpty();
        }

        /**
         * A subscribing event processor fed by a {@code SimpleEventBus} invokes a Saga from within
         * {@code PREPARE_COMMIT}, where registering for {@code PREPARE_COMMIT} itself is rejected.
         */
        @Test
        void aCallMadeFromWithinPrepareCommitIsStillDeferredAndRuns() {
            // given
            UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();
            AtomicInteger scheduledDuringPrepareCommit = new AtomicInteger(-1);
            unitOfWork.runOnPrepareCommit(context -> new TestScope(context).run(() -> {
                testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE);
                scheduledDuringPrepareCommit.set(testSubject.scheduled.size());
            }));

            // when
            unitOfWork.execute().orTimeout(1, TimeUnit.SECONDS).join();

            // then
            assertThat(scheduledDuringPrepareCommit).hasValue(0);
            assertThat(testSubject.scheduled).hasSize(1);
        }

        @Test
        void deferredCallsRunInTheOrderTheyWereMade() {
            // when
            runInUnitOfWork(context -> {
                new TestScope(context).run(() -> {
                    testSubject.schedule(Instant.now(), "first", "payload", EXPLICIT_SCOPE);
                    testSubject.cancelAll("first");
                });
                new TestScope(context).run(() -> testSubject.schedule(Instant.now(), "second", "payload",
                                                                     EXPLICIT_SCOPE));
            });

            // then
            assertThat(timeline).containsExactly("manager:schedule first",
                                                 "manager:cancelAll first",
                                                 "manager:schedule second");
        }

        @Test
        void deferredCallsRunAfterTheSagaWriteAndBeforeCommit() {
            // given
            UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();
            unitOfWork.runOn(AnnotatedSagaRepository.WRITE_SAGA, context -> timeline.add("sagaWrite"));
            unitOfWork.runOnCommit(context -> timeline.add("commit"));

            // when
            unitOfWork.executeWithResult(context -> {
                new TestScope(context).run(
                        () -> testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE)
                );
                return CompletableFuture.completedFuture(null);
            }).orTimeout(1, TimeUnit.SECONDS).join();

            // then
            assertThat(timeline).containsExactly("sagaWrite", "manager:schedule deadlineName", "commit");
        }

        /**
         * Axon Framework 4 checked the phase on every registration, so every call made too late failed. The same holds
         * here for every late call, not only the first one.
         */
        @Test
        void everyCallDeferredAfterTheDeadlinePhaseStartedIsRejected() {
            // given
            UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();
            List<Throwable> lateFailures = new CopyOnWriteArrayList<>();
            ProcessingLifecycle.Phase afterDeadlinePhase = () -> AbstractDeadlineManager.RUN_DEADLINE_CALLS.order() + 1;
            unitOfWork.runOn(afterDeadlinePhase, context -> new TestScope(context).run(() -> {
                for (String deadlineName : List.of("late1", "late2")) {
                    try {
                        testSubject.schedule(Instant.now(), deadlineName, "payload", EXPLICIT_SCOPE);
                    } catch (IllegalStateException e) {
                        lateFailures.add(e);
                    }
                }
            }));

            // when
            unitOfWork.executeWithResult(context -> {
                new TestScope(context).run(
                        () -> testSubject.schedule(Instant.now(), "inTime", "payload", EXPLICIT_SCOPE)
                );
                return CompletableFuture.completedFuture(null);
            }).orTimeout(1, TimeUnit.SECONDS).join();

            // then
            assertThat(lateFailures).hasSize(2);
            assertThat(timeline).containsExactly("manager:schedule inTime");
        }

        @Test
        void eachDeadlineManagerRunsItsOwnDeferredCalls() {
            // given
            RecordingDeadlineManager otherManager = new RecordingDeadlineManager("other", timeline);

            // when
            runInUnitOfWork(context -> new TestScope(context).run(() -> {
                testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE);
                otherManager.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE);
            }));

            // then
            assertThat(testSubject.scheduled).hasSize(1);
            assertThat(otherManager.scheduled).hasSize(1);
        }
    }

    @Nested
    class DispatchInterceptors {

        @Test
        void interceptorsRunWithinTheDeferredCall() {
            // given
            AtomicInteger interceptions = new AtomicInteger();
            AtomicInteger interceptionsDuringInvocation = new AtomicInteger(-1);
            testSubject.registerDispatchInterceptor((message, context, chain) -> {
                interceptions.incrementAndGet();
                return chain.proceed(message, context);
            });

            // when
            runInUnitOfWork(context -> new TestScope(context).run(() -> {
                testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE);
                interceptionsDuringInvocation.set(interceptions.get());
            }));

            // then
            assertThat(interceptionsDuringInvocation).hasValue(0);
            assertThat(interceptions).hasValue(1);
        }

        @Test
        void interceptorsDoNotRunWhenTheContextRollsBack() {
            // given
            AtomicInteger interceptions = new AtomicInteger();
            testSubject.registerDispatchInterceptor((message, context, chain) -> {
                interceptions.incrementAndGet();
                return chain.proceed(message, context);
            });

            // when
            CompletableFuture<Object> result = UnitOfWorkTestUtils.aUnitOfWork().executeWithResult(context -> {
                new TestScope(context).run(
                        () -> testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE)
                );
                return CompletableFuture.failedFuture(new IllegalStateException("handler failure"));
            });

            // then
            assertThatThrownBy(() -> result.orTimeout(1, TimeUnit.SECONDS).join())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(interceptions).hasValue(0);
        }

        @Test
        void theScheduledMessageIsTheInterceptedOne() {
            // given
            testSubject.registerDispatchInterceptor(
                    (message, context, chain) -> chain.proceed(message.andMetadata(Map.of("key", "value")), context)
            );

            // when
            testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE);

            // then
            assertThat(testSubject.scheduled).singleElement()
                                             .satisfies(call -> assertThat(call.message().metadata())
                                                     .containsEntry("key", "value"));
        }

        @Test
        void aFailingInterceptorSurfacesItsExceptionWhenTheCallRunsImmediately() {
            // given
            testSubject.registerDispatchInterceptor((message, context, chain) -> {
                throw new IllegalStateException("interceptor failure");
            });

            // when / then
            assertThatThrownBy(
                    () -> testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE)
            ).isInstanceOf(IllegalStateException.class).hasMessage("interceptor failure");
            assertThat(testSubject.scheduled).isEmpty();
        }

        @Test
        void aFailingInterceptorFailsTheContextWhenTheCallIsDeferred() {
            // given
            testSubject.registerDispatchInterceptor((message, context, chain) -> {
                throw new IllegalStateException("interceptor failure");
            });

            // when
            CompletableFuture<Object> result = UnitOfWorkTestUtils.aUnitOfWork().executeWithResult(context -> {
                new TestScope(context).run(
                        () -> testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE)
                );
                return CompletableFuture.completedFuture(null);
            });

            // then
            assertThatThrownBy(() -> result.orTimeout(1, TimeUnit.SECONDS).join())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("interceptor failure");
            assertThat(testSubject.scheduled).isEmpty();
        }

        /**
         * The counterpart of an Axon Framework 4 interceptor returning {@code null}, which then got scheduled as
         * {@code null}. Passing it on is left to the implementation, as it was there.
         */
        @Test
        void anInterceptorEndingTheChainWithoutAMessageYieldsNull() {
            // given
            testSubject.registerDispatchInterceptor((message, context, chain) -> MessageStream.empty());

            // when
            testSubject.schedule(Instant.now(), "deadlineName", "payload", EXPLICIT_SCOPE);

            // then
            assertThat(testSubject.scheduled).singleElement()
                                             .extracting(ScheduledCall::message)
                                             .isNull();
        }
    }

    private static void runInUnitOfWork(Consumer<ProcessingContext> invocation) {
        UnitOfWorkTestUtils.aUnitOfWork()
                           .executeWithResult(context -> {
                               invocation.accept(context);
                               return CompletableFuture.completedFuture(null);
                           })
                           .orTimeout(1, TimeUnit.SECONDS)
                           .join();
    }

    /**
     * Stands in for the scope a Saga starts around its handler invocation.
     */
    private static final class TestScope extends ContextAwareScope {

        private final ProcessingContext context;

        private TestScope(ProcessingContext context) {
            this.context = context;
        }

        private void run(Runnable task) {
            startScope();
            try {
                task.run();
            } finally {
                endScope();
            }
        }

        @Override
        public ProcessingContext processingContext() {
            return context;
        }

        @Override
        public ScopeDescriptor describeScope() {
            return () -> "testScope";
        }
    }

    private record ScheduledCall(String scheduleId, @Nullable DeadlineMessage message, ScopeDescriptor scope) {

    }

    /**
     * A test-only {@link AbstractDeadlineManager} recording every call instead of scheduling against a backend. Each
     * method follows the Axon Framework 4 {@code SimpleDeadlineManager}: whatever the caller needs back is computed
     * right away, everything else runs through {@link #runOnPrepareCommitOrNow(Runnable)}.
     */
    private static final class RecordingDeadlineManager extends AbstractDeadlineManager {

        private final String name;
        private final List<String> timeline;
        private final List<ScheduledCall> scheduled = new CopyOnWriteArrayList<>();

        private RecordingDeadlineManager(String name, List<String> timeline) {
            this.name = name;
            this.timeline = timeline;
        }

        @Override
        public String schedule(Instant triggerDateTime,
                               String deadlineName,
                               @Nullable Object messageOrPayload,
                               ScopeDescriptor deadlineScope) {
            DeadlineMessage deadlineMessage = asDeadlineMessage(deadlineName, messageOrPayload, triggerDateTime);
            String scheduleId = deadlineMessage.identifier();
            runOnPrepareCommitOrNow(() -> {
                DeadlineMessage intercepted = processDispatchInterceptors(deadlineMessage);
                scheduled.add(new ScheduledCall(scheduleId, intercepted, deadlineScope));
                timeline.add(name + ":schedule " + deadlineName);
            });
            return scheduleId;
        }

        @Override
        public void cancelSchedule(String deadlineName, String scheduleId) {
            runOnPrepareCommitOrNow(() -> timeline.add(name + ":cancelSchedule " + deadlineName + "/" + scheduleId));
        }

        @Override
        public void cancelAll(String deadlineName) {
            runOnPrepareCommitOrNow(() -> timeline.add(name + ":cancelAll " + deadlineName));
        }

        @Override
        public void cancelAllWithinScope(String deadlineName, ScopeDescriptor scope) {
            runOnPrepareCommitOrNow(() -> timeline.add(
                    name + ":cancelAllWithinScope " + deadlineName + "@" + scope.scopeDescription()
            ));
        }
    }
}
