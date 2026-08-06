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

package org.axonframework.hunt.probe.unitofwork;

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle.Phase;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycleInterceptor;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkConfiguration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transparency and coverage of the {@link ProcessingLifecycleInterceptor} seam on the {@link UnitOfWork}
 * (claim C46 in {@code docs/testing-plans/axon-hunt.md}).
 * <p>
 * The seam's contract: the interceptor wraps every dispatch site (phase actions, completion-handler dispatch,
 * error-handler dispatch) on the thread running the action; contributors compose via
 * {@link UnitOfWorkConfiguration#addLifecycleInterceptor(ProcessingLifecycleInterceptor)} without clobbering one
 * another; {@link UnitOfWorkConfiguration#forcedSameThreadInvocation()} preserves the installed interceptor; and an
 * installed pass-through interceptor changes no outcome relative to the no-interceptor baseline -- the unit of
 * work's result, its error cause, and the set of completion/error handlers that run are identical.
 */
class LifecycleInterceptorTransparencyTest {

    private record Dispatch(String kind, @Nullable Integer phaseOrder, String thread) {

    }

    private static final class RecordingInterceptor implements ProcessingLifecycleInterceptor {

        private final String label;
        private final List<String> log;
        private final List<Dispatch> dispatches = new CopyOnWriteArrayList<>();

        private RecordingInterceptor(String label, List<String> log) {
            this.label = label;
            this.log = log;
        }

        private RecordingInterceptor() {
            this("interceptor", new CopyOnWriteArrayList<>());
        }

        @Override
        public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                   Supplier<CompletableFuture<?>> action) {
            dispatches.add(new Dispatch("phase", phase.order(), Thread.currentThread().getName()));
            log.add(label + ":phase");
            return action.get();
        }

        @Override
        public void interceptCompletion(ProcessingContext context, Runnable action) {
            dispatches.add(new Dispatch("completion", null, Thread.currentThread().getName()));
            log.add(label + ":completion");
            action.run();
        }

        @Override
        public void interceptError(ProcessingContext context, @Nullable Phase failedPhase, Throwable cause,
                                   Runnable action) {
            dispatches.add(new Dispatch("error", failedPhase == null ? null : failedPhase.order(),
                                        Thread.currentThread().getName()));
            log.add(label + ":error");
            action.run();
        }
    }

    private static UnitOfWork unitOfWork(Function<UnitOfWorkConfiguration, UnitOfWorkConfiguration> customization) {
        return new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE).create("interceptor-probe",
                                                                                    customization);
    }

    @Nested
    class EveryDispatchSiteIsCovered {

        @Test
        void phaseActionsCompletionHandlersAndTheirThreadsAreAllRecorded() {
            // given a unit of work with actions in three phases and two completion handlers
            RecordingInterceptor interceptor = new RecordingInterceptor();
            UnitOfWork unitOfWork = unitOfWork(configuration -> configuration.addLifecycleInterceptor(interceptor));
            List<String> executed = new CopyOnWriteArrayList<>();
            unitOfWork.runOnPreInvocation(context -> executed.add("preInvocation"));
            unitOfWork.runOnInvocation(context -> executed.add("invocation"));
            unitOfWork.runOnAfterCommit(context -> executed.add("afterCommit"));
            unitOfWork.whenComplete(context -> executed.add("complete-1"));
            unitOfWork.whenComplete(context -> executed.add("complete-2"));

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), Duration.ofSeconds(30));

            // then every action ran, and every dispatch site passed through the interceptor on the action's thread
            assertThat(executed).containsExactly("preInvocation", "invocation", "afterCommit",
                                                 "complete-1", "complete-2");
            assertThat(interceptor.dispatches.stream().filter(d -> d.kind().equals("phase"))).hasSize(3);
            assertThat(interceptor.dispatches.stream().filter(d -> d.kind().equals("completion"))).hasSize(2);
            assertThat(interceptor.dispatches.stream().filter(d -> d.kind().equals("error"))).isEmpty();
            assertThat(interceptor.dispatches)
                    .allSatisfy(dispatch -> assertThat(dispatch.thread()).isNotBlank());
        }

        @Test
        void errorHandlerDispatchIsInterceptedAndTheOriginalCauseStillPropagates() {
            // given an invocation action that fails and two error handlers, the first of which itself throws
            RecordingInterceptor interceptor = new RecordingInterceptor();
            UnitOfWork unitOfWork = unitOfWork(configuration -> configuration.addLifecycleInterceptor(interceptor));
            IllegalStateException failure = new IllegalStateException("invocation failed");
            List<String> handled = new CopyOnWriteArrayList<>();
            unitOfWork.runOnInvocation(context -> {
                throw failure;
            });
            unitOfWork.onError((context, phase, error) -> {
                handled.add("handler-1:" + error.getMessage());
                throw new RuntimeException("error handler misbehaves");
            });
            unitOfWork.onError((context, phase, error) -> handled.add("handler-2:" + error.getMessage()));

            // when / then the unit of work still fails with the ORIGINAL cause, not the error handler's
            assertThatThrownBy(() -> unitOfWork.execute().orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                               .join())
                    .isInstanceOf(CompletionException.class)
                    .hasCause(failure);

            // then both error handlers ran despite the first one throwing, and both dispatches were intercepted
            assertThat(handled).containsExactly("handler-1:invocation failed", "handler-2:invocation failed");
            assertThat(interceptor.dispatches.stream().filter(d -> d.kind().equals("error"))).hasSize(2);
        }
    }

    @Nested
    class BaselineParity {

        /** Runs the failing-invocation workload and reports what the caller observes. */
        private record Outcome(Throwable cause, List<String> handled, boolean completionRan) {

        }

        private Outcome run(Function<UnitOfWorkConfiguration, UnitOfWorkConfiguration> customization) {
            UnitOfWork unitOfWork = unitOfWork(customization);
            IllegalStateException failure = new IllegalStateException("invocation failed");
            List<String> handled = new CopyOnWriteArrayList<>();
            List<String> completions = new CopyOnWriteArrayList<>();
            unitOfWork.runOnInvocation(context -> {
                throw failure;
            });
            unitOfWork.onError((context, phase, error) -> {
                handled.add("handler-1");
                throw new RuntimeException("error handler misbehaves");
            });
            unitOfWork.onError((context, phase, error) -> handled.add("handler-2"));
            unitOfWork.whenComplete(context -> completions.add("completion"));
            Throwable cause;
            try {
                unitOfWork.execute().orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).join();
                throw new AssertionError("The unit of work unexpectedly succeeded");
            } catch (CompletionException e) {
                cause = e.getCause();
            }
            return new Outcome(cause, handled, !completions.isEmpty());
        }

        @Test
        void aPassThroughInterceptorChangesNoOutcomeRelativeToTheNoInterceptorBaseline() {
            // given / when the same failing workload runs without an interceptor and with a pass-through one
            Outcome baseline = run(configuration -> configuration);
            Outcome intercepted = run(configuration ->
                                              configuration.addLifecycleInterceptor(new RecordingInterceptor()));

            // then the observable outcome is identical
            assertThat(intercepted.cause()).isInstanceOf(baseline.cause().getClass());
            assertThat(intercepted.cause().getMessage()).isEqualTo(baseline.cause().getMessage());
            assertThat(intercepted.handled()).isEqualTo(baseline.handled());
            assertThat(intercepted.completionRan()).isEqualTo(baseline.completionRan());
        }
    }

    @Nested
    class CompositionAndSameThreadInvocation {

        @Test
        void twoContributorsComposeOuterToInnerWithoutClobbering() {
            // given two interceptors registered through addLifecycleInterceptor
            List<String> log = new CopyOnWriteArrayList<>();
            RecordingInterceptor outer = new RecordingInterceptor("outer", log);
            RecordingInterceptor inner = new RecordingInterceptor("inner", log);
            UnitOfWork unitOfWork = unitOfWork(configuration -> configuration.addLifecycleInterceptor(outer)
                                                                             .addLifecycleInterceptor(inner));
            unitOfWork.runOnInvocation(context -> {
            });

            // when
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), Duration.ofSeconds(30));

            // then both saw the phase dispatch, first-registered outermost
            assertThat(log).startsWith("outer:phase", "inner:phase");
            assertThat(outer.dispatches).isNotEmpty();
            assertThat(inner.dispatches).isNotEmpty();
        }

        @Test
        void forcedSameThreadInvocationPreservesTheInterceptorAndRunsEveryDispatchOnTheCallingThread() {
            // given an interceptor installed before forcedSameThreadInvocation is applied
            RecordingInterceptor interceptor = new RecordingInterceptor();
            UnitOfWorkConfiguration configuration = new UnitOfWorkConfiguration(
                    org.axonframework.common.DirectExecutor.instance(), true, List.of())
                    .addLifecycleInterceptor(interceptor)
                    .forcedSameThreadInvocation();
            assertThat(configuration.lifecycleInterceptor()).isNotNull();

            UnitOfWork unitOfWork = unitOfWork(ignored -> configuration);
            List<String> executed = new CopyOnWriteArrayList<>();
            unitOfWork.runOnInvocation(context -> executed.add("invocation"));
            unitOfWork.runOnCommit(context -> executed.add("commit"));
            unitOfWork.whenComplete(context -> executed.add("completion"));

            // when
            String callingThread = Thread.currentThread().getName();
            FutureUtils.joinAndUnwrap(unitOfWork.execute(), Duration.ofSeconds(30));

            // then the interceptor survived the switch and every dispatch ran on the calling thread
            assertThat(executed).containsExactly("invocation", "commit", "completion");
            assertThat(interceptor.dispatches.stream().filter(d -> d.kind().equals("phase"))).hasSize(2);
            assertThat(interceptor.dispatches.stream().filter(d -> d.kind().equals("completion"))).hasSize(1);
            assertThat(interceptor.dispatches)
                    .allSatisfy(dispatch -> assertThat(dispatch.thread()).isEqualTo(callingThread));
        }
    }

    // Static default check: the interceptor-less construction path installs no interceptor, so the
    // zero-overhead direct path is the default.
    @Test
    void noInterceptorIsInstalledByDefault() {
        UnitOfWorkConfiguration configuration = new UnitOfWorkConfiguration(
                org.axonframework.common.DirectExecutor.instance(), true, List.of());
        assertThat(configuration.lifecycleInterceptor()).isNull();
    }
}
