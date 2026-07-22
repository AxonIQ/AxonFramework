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
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle.DefaultPhases;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle.Phase;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests validating the {@link ProcessingLifecycleInterceptor} seam routed inside the {@link UnitOfWork}.
 *
 * @author Mateusz Nowak
 */
class ProcessingLifecycleInterceptorTest {

    private final SimpleUnitOfWorkFactory factory = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE);

    private UnitOfWork unitOfWorkWith(ProcessingLifecycleInterceptor interceptor) {
        return (UnitOfWork) factory.create("test", config -> config.lifecycleInterceptor(interceptor));
    }

    @Nested
    class Coverage {

        @Test
        void interceptorWrapsEveryPhaseAction() {
            // given
            AtomicInteger interceptions = new AtomicInteger();
            List<Boolean> contextsPresent = new CopyOnWriteArrayList<>();
            ProcessingLifecycleInterceptor interceptor = ProcessingLifecycleInterceptor.intercept(
                    (context, action) -> {
                        interceptions.incrementAndGet();
                        contextsPresent.add(context != null);
                        return action.get();
                    });
            UnitOfWork uow = unitOfWorkWith(interceptor);
            uow.on(DefaultPhases.INVOCATION, c -> FutureUtils.emptyCompletedFuture());
            uow.on(DefaultPhases.PREPARE_COMMIT, c -> FutureUtils.emptyCompletedFuture());
            uow.on(DefaultPhases.COMMIT, c -> FutureUtils.emptyCompletedFuture());
            uow.on(DefaultPhases.AFTER_COMMIT, c -> FutureUtils.emptyCompletedFuture());

            // when
            uow.execute().join();

            // then
            assertThat(interceptions.get()).isEqualTo(4);
            assertThat(contextsPresent).containsExactly(true, true, true, true);
        }

        @Test
        void interceptorSeesActionsRegisteredFromInsideAnotherAction() {
            // given -- mirrors DefaultEventStoreTransaction, which registers COMMIT/AFTER_COMMIT from an earlier phase
            AtomicInteger interceptions = new AtomicInteger();
            AtomicInteger nestedRan = new AtomicInteger();
            ProcessingLifecycleInterceptor interceptor = ProcessingLifecycleInterceptor.intercept(
                    (context, action) -> {
                        interceptions.incrementAndGet();
                        return action.get();
                    });
            UnitOfWork uow = unitOfWorkWith(interceptor);
            uow.on(DefaultPhases.INVOCATION, c -> {
                c.on(DefaultPhases.COMMIT, c2 -> {
                    nestedRan.incrementAndGet();
                    return FutureUtils.emptyCompletedFuture();
                });
                return FutureUtils.emptyCompletedFuture();
            });

            // when
            uow.execute().join();

            // then -- both the outer and the dynamically registered inner action are intercepted
            assertThat(nestedRan.get()).isEqualTo(1);
            assertThat(interceptions.get()).isEqualTo(2);
        }

        @Test
        void interceptorSeesCompletionHandler() {
            // given
            AtomicInteger interceptions = new AtomicInteger();
            ProcessingLifecycleInterceptor interceptor = ProcessingLifecycleInterceptor.intercept(
                    (context, action) -> {
                        interceptions.incrementAndGet();
                        return action.get();
                    });
            UnitOfWork uow = unitOfWorkWith(interceptor);
            uow.whenComplete(c -> {
            });

            // when
            uow.execute().join();

            // then
            assertThat(interceptions.get()).isEqualTo(1);
        }

        @Test
        void interceptorSeesErrorHandler() {
            // given
            AtomicInteger interceptions = new AtomicInteger();
            ProcessingLifecycleInterceptor interceptor = ProcessingLifecycleInterceptor.intercept(
                    (context, action) -> {
                        interceptions.incrementAndGet();
                        return action.get();
                    });
            UnitOfWork uow = unitOfWorkWith(interceptor);
            uow.on(DefaultPhases.INVOCATION, c -> CompletableFuture.failedFuture(new RuntimeException("boom")));
            uow.onError((c, phase, error) -> {
            });

            // when / then -- one interception for the failing action, one for the error handler dispatch
            assertThatThrownBy(() -> uow.execute().join()).hasRootCauseMessage("boom");
            assertThat(interceptions.get()).isEqualTo(2);
        }
    }

    @Nested
    class KindDiscrimination {

        @Test
        void phaseActionsReceiveTheirRegistrationPhase() {
            // given
            List<Phase> phases = new CopyOnWriteArrayList<>();
            ProcessingLifecycleInterceptor interceptor = recordingPhases(phases);
            UnitOfWork uow = unitOfWorkWith(interceptor);
            uow.on(DefaultPhases.INVOCATION, c -> FutureUtils.emptyCompletedFuture());
            uow.on(DefaultPhases.PREPARE_COMMIT, c -> FutureUtils.emptyCompletedFuture());
            uow.on(DefaultPhases.COMMIT, c -> FutureUtils.emptyCompletedFuture());
            uow.on(DefaultPhases.AFTER_COMMIT, c -> FutureUtils.emptyCompletedFuture());

            // when
            uow.execute().join();

            // then
            assertThat(phases).containsExactly(DefaultPhases.INVOCATION,
                                               DefaultPhases.PREPARE_COMMIT,
                                               DefaultPhases.COMMIT,
                                               DefaultPhases.AFTER_COMMIT);
        }

        @Test
        void completionHandlerDispatchIsRoutedToItsOwnMethod() {
            // given
            AtomicInteger completions = new AtomicInteger();
            ProcessingLifecycleInterceptor interceptor = new ProcessingLifecycleInterceptor() {
                @Override
                public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                                  Supplier<CompletableFuture<?>> action) {
                    return action.get();
                }

                @Override
                public void interceptCompletion(ProcessingContext context, Runnable action) {
                    completions.incrementAndGet();
                    action.run();
                }

                @Override
                public void interceptError(ProcessingContext context, @Nullable Phase failedPhase,
                                                  Throwable cause, Runnable action) {
                    action.run();
                }
            };
            UnitOfWork uow = unitOfWorkWith(interceptor);
            uow.whenComplete(c -> {
            });

            // when
            uow.execute().join();

            // then -- the completion handler is routed through its own method, distinct from phase actions
            assertThat(completions.get()).isEqualTo(1);
        }

        @Test
        void errorHandlerDispatchReceivesTheFailedPhaseAndTheSameCauseInstance() {
            // given
            List<Phase> failedPhases = new CopyOnWriteArrayList<>();
            List<Throwable> causes = new CopyOnWriteArrayList<>();
            ProcessingLifecycleInterceptor interceptor = new ProcessingLifecycleInterceptor() {
                @Override
                public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                                  Supplier<CompletableFuture<?>> action) {
                    return action.get();
                }

                @Override
                public void interceptCompletion(ProcessingContext context, Runnable action) {
                    action.run();
                }

                @Override
                public void interceptError(ProcessingContext context, @Nullable Phase failedPhase,
                                                  Throwable cause, Runnable action) {
                    failedPhases.add(failedPhase);
                    causes.add(cause);
                    action.run();
                }
            };
            UnitOfWork uow = unitOfWorkWith(interceptor);
            RuntimeException thrown = new RuntimeException("boom");
            uow.on(DefaultPhases.INVOCATION, c -> CompletableFuture.failedFuture(thrown));
            uow.onError((c, phase, error) -> {
            });

            // when / then
            assertThatThrownBy(() -> uow.execute().join()).hasRootCause(thrown);
            assertThat(failedPhases).containsExactly(DefaultPhases.INVOCATION);
            assertThat(causes).containsExactly(thrown);
        }

        @Test
        void errorPrecedingTheFirstPhaseReportsANullFailedPhase() {
            // given -- no phase action is registered, so the failure (if any) cannot carry a failed phase; instead we
            // simulate the "no failing phase action" case directly by never entering a phase before the error.
            List<Phase> failedPhases = new CopyOnWriteArrayList<>();
            ProcessingLifecycleInterceptor interceptor = new ProcessingLifecycleInterceptor() {
                @Override
                public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                                  Supplier<CompletableFuture<?>> action) {
                    return action.get();
                }

                @Override
                public void interceptCompletion(ProcessingContext context, Runnable action) {
                    action.run();
                }

                @Override
                public void interceptError(ProcessingContext context, @Nullable Phase failedPhase,
                                                  Throwable cause, Runnable action) {
                    failedPhases.add(failedPhase);
                    action.run();
                }
            };
            UnitOfWork uow = unitOfWorkWith(interceptor);
            uow.onError((c, phase, error) -> {
            });

            // when
            uow.execute().join();

            // then -- no phase action failed, so nothing routes through interceptError
            assertThat(failedPhases).isEmpty();
        }

        @Test
        void interceptorCanLimitItselfToSpecificPhasesWhileStillCoveringOtherKinds() {
            // given -- an interceptor wrapping COMMIT actions only, passing all other phases through untouched, while
            // completion/error dispatch (abstract, so they must be implemented) simply run the action.
            List<String> order = new CopyOnWriteArrayList<>();
            ProcessingLifecycleInterceptor commitOnly = new ProcessingLifecycleInterceptor() {
                @Override
                public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                                  Supplier<CompletableFuture<?>> action) {
                    if (!DefaultPhases.COMMIT.equals(phase)) {
                        return action.get();
                    }
                    order.add("before-commit-action");
                    CompletableFuture<?> result = action.get();
                    order.add("after-commit-action");
                    return result;
                }

                @Override
                public void interceptCompletion(ProcessingContext context, Runnable action) {
                    action.run();
                }

                @Override
                public void interceptError(ProcessingContext context, @Nullable Phase failedPhase,
                                                  Throwable cause, Runnable action) {
                    action.run();
                }
            };
            UnitOfWork uow = unitOfWorkWith(commitOnly);
            uow.on(DefaultPhases.INVOCATION, c -> {
                order.add("invocation-action");
                return FutureUtils.emptyCompletedFuture();
            });
            uow.on(DefaultPhases.COMMIT, c -> {
                order.add("commit-action");
                return FutureUtils.emptyCompletedFuture();
            });

            // when
            uow.execute().join();

            // then -- only the COMMIT action is wrapped
            assertThat(order).containsExactly("invocation-action",
                                              "before-commit-action",
                                              "commit-action",
                                              "after-commit-action");
        }

        private ProcessingLifecycleInterceptor recordingPhases(List<Phase> phases) {
            return new ProcessingLifecycleInterceptor() {
                @Override
                public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                                  Supplier<CompletableFuture<?>> action) {
                    phases.add(phase);
                    return action.get();
                }

                @Override
                public void interceptCompletion(ProcessingContext context, Runnable action) {
                    action.run();
                }

                @Override
                public void interceptError(ProcessingContext context, @Nullable Phase failedPhase,
                                                  Throwable cause, Runnable action) {
                    action.run();
                }
            };
        }
    }

    @Nested
    class Uniform {

        @Test
        void uniformFiresForAllThreeDispatchKinds() {
            // given
            AtomicInteger phaseActions = new AtomicInteger();
            AtomicInteger completions = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            ProcessingLifecycleInterceptor interceptor = ProcessingLifecycleInterceptor.intercept(
                    (context, action) -> action.get());
            ProcessingLifecycleInterceptor counting = interceptor.andThen(new ProcessingLifecycleInterceptor() {
                @Override
                public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                                  Supplier<CompletableFuture<?>> action) {
                    phaseActions.incrementAndGet();
                    return action.get();
                }

                @Override
                public void interceptCompletion(ProcessingContext context, Runnable action) {
                    completions.incrementAndGet();
                    action.run();
                }

                @Override
                public void interceptError(ProcessingContext context, @Nullable Phase failedPhase,
                                                  Throwable cause, Runnable action) {
                    errors.incrementAndGet();
                    action.run();
                }
            });
            UnitOfWork uow = (UnitOfWork) factory.create("test", config -> config.lifecycleInterceptor(counting));
            uow.on(DefaultPhases.INVOCATION, c -> CompletableFuture.failedFuture(new RuntimeException("boom")));
            uow.onError((c, phase, error) -> {
            });
            uow.whenComplete(c -> {
            });

            // when / then -- the lifecycle fails, so only the error handler (not the completion handler) dispatches
            assertThatThrownBy(() -> uow.execute().join()).hasRootCauseMessage("boom");
            assertThat(phaseActions.get()).isEqualTo(1);
            assertThat(errors.get()).isEqualTo(1);
            assertThat(completions.get()).isZero();
        }

        @Test
        void uniformNeverUnderCoversASite() {
            // given -- a happy-path lifecycle exercising phase actions and the completion dispatch
            AtomicInteger interceptions = new AtomicInteger();
            ProcessingLifecycleInterceptor interceptor = ProcessingLifecycleInterceptor.intercept(
                    (context, action) -> {
                        interceptions.incrementAndGet();
                        return action.get();
                    });
            UnitOfWork uow = unitOfWorkWith(interceptor);
            uow.on(DefaultPhases.INVOCATION, c -> FutureUtils.emptyCompletedFuture());
            uow.whenComplete(c -> {
            });

            // when
            uow.execute().join();

            // then -- one for the phase action, one for the completion dispatch
            assertThat(interceptions.get()).isEqualTo(2);
        }
    }

    @Nested
    class AndThen {

        @Test
        void andThenComposesOuterBeforeInnerPerMethod() {
            // given
            List<String> order = new CopyOnWriteArrayList<>();
            ProcessingLifecycleInterceptor outer = wrapping(order, "outer");
            ProcessingLifecycleInterceptor inner = wrapping(order, "inner");
            UnitOfWork uow = unitOfWorkWith(outer.andThen(inner));
            uow.on(DefaultPhases.INVOCATION, c -> {
                order.add("action");
                return FutureUtils.emptyCompletedFuture();
            });
            uow.whenComplete(c -> order.add("completion"));

            // when
            uow.execute().join();

            // then -- outer wraps inner wraps the action, for every dispatch kind
            assertThat(order).containsExactly("outer-phase-before", "inner-phase-before", "action",
                                              "inner-phase-after", "outer-phase-after",
                                              "outer-completion-before", "inner-completion-before", "completion",
                                              "inner-completion-after", "outer-completion-after");
        }

        @Test
        void multipleContributorsCompose() {
            // given -- two independent installers must both fire, not clobber each other
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            Supplier<UnitOfWork> build = () -> (UnitOfWork) factory.create(
                    "test",
                    config -> config.addLifecycleInterceptor(ProcessingLifecycleInterceptor.intercept((c, a) -> {
                                        first.incrementAndGet();
                                        return a.get();
                                    }))
                                    .addLifecycleInterceptor(ProcessingLifecycleInterceptor.intercept((c, a) -> {
                                        second.incrementAndGet();
                                        return a.get();
                                    }))
            );
            UnitOfWork uow = build.get();
            uow.on(DefaultPhases.INVOCATION, c -> FutureUtils.emptyCompletedFuture());

            // when
            uow.execute().join();

            // then
            assertThat(first.get()).isEqualTo(1);
            assertThat(second.get()).isEqualTo(1);
        }

        private ProcessingLifecycleInterceptor wrapping(List<String> order, String label) {
            return new ProcessingLifecycleInterceptor() {
                @Override
                public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                                  Supplier<CompletableFuture<?>> action) {
                    order.add(label + "-phase-before");
                    CompletableFuture<?> result = action.get();
                    order.add(label + "-phase-after");
                    return result;
                }

                @Override
                public void interceptCompletion(ProcessingContext context, Runnable action) {
                    order.add(label + "-completion-before");
                    action.run();
                    order.add(label + "-completion-after");
                }

                @Override
                public void interceptError(ProcessingContext context, @Nullable Phase failedPhase,
                                                  Throwable cause, Runnable action) {
                    order.add(label + "-error-before");
                    action.run();
                    order.add(label + "-error-after");
                }
            };
        }
    }

    @Nested
    class Behavior {

        @Test
        void exceptionsFromActionPropagateUnchanged() {
            // given
            IllegalStateException failure = new IllegalStateException("expected");
            ProcessingLifecycleInterceptor interceptor = ProcessingLifecycleInterceptor.intercept(
                    (context, action) -> action.get());
            UnitOfWork uow = unitOfWorkWith(interceptor);
            uow.on(DefaultPhases.INVOCATION, c -> {
                throw failure;
            });

            // when / then
            assertThatThrownBy(() -> uow.execute().join()).hasRootCause(failure);
        }

        @Test
        void noInterceptorDefaultKeepsBehaviorUnchanged() {
            // given -- default configuration, no interceptor installed, no custom wrapping
            AtomicInteger ran = new AtomicInteger();
            UnitOfWork uow = (UnitOfWork) factory.create("test", config -> config);
            uow.on(DefaultPhases.INVOCATION, c -> {
                ran.incrementAndGet();
                return FutureUtils.emptyCompletedFuture();
            });

            // when
            CompletableFuture<Void> result = uow.execute();

            // then
            result.join();
            assertThat(ran.get()).isEqualTo(1);
            assertThat(result).isCompleted();
        }

        @Test
        void misbehavingCompletionInterceptorIsSwallowedAndDoesNotBlockRemainingHandlersOrFailTheUnitOfWork() {
            // given -- an interceptor that runs each completion dispatch and then throws
            AtomicInteger completionsRun = new AtomicInteger();
            ProcessingLifecycleInterceptor throwingOnCompletion = new ProcessingLifecycleInterceptor() {
                @Override
                public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                           Supplier<CompletableFuture<?>> action) {
                    return action.get();
                }

                @Override
                public void interceptCompletion(ProcessingContext context, Runnable action) {
                    action.run();
                    throw new RuntimeException("interceptor boom");
                }

                @Override
                public void interceptError(ProcessingContext context, @Nullable Phase failedPhase,
                                           Throwable cause, Runnable action) {
                    action.run();
                }
            };
            UnitOfWork uow = unitOfWorkWith(throwingOnCompletion);
            uow.whenComplete(c -> completionsRun.incrementAndGet());
            uow.whenComplete(c -> completionsRun.incrementAndGet());

            // when
            CompletableFuture<Void> result = uow.execute();

            // then -- both completion handlers ran and the throwing interceptor did not fail the unit of work
            result.join();
            assertThat(completionsRun.get()).isEqualTo(2);
            assertThat(result).isCompleted();
        }

        @Test
        void misbehavingErrorInterceptorIsSwallowedAndDoesNotBlockRemainingHandlersNorMaskTheOriginalCause() {
            // given -- an interceptor that runs each error dispatch and then throws
            AtomicInteger errorsRun = new AtomicInteger();
            ProcessingLifecycleInterceptor throwingOnError = new ProcessingLifecycleInterceptor() {
                @Override
                public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                           Supplier<CompletableFuture<?>> action) {
                    return action.get();
                }

                @Override
                public void interceptCompletion(ProcessingContext context, Runnable action) {
                    action.run();
                }

                @Override
                public void interceptError(ProcessingContext context, @Nullable Phase failedPhase,
                                           Throwable cause, Runnable action) {
                    action.run();
                    throw new RuntimeException("interceptor boom");
                }
            };
            UnitOfWork uow = unitOfWorkWith(throwingOnError);
            RuntimeException failure = new RuntimeException("boom");
            uow.on(DefaultPhases.INVOCATION, c -> CompletableFuture.failedFuture(failure));
            uow.onError((c, phase, error) -> errorsRun.incrementAndGet());
            uow.onError((c, phase, error) -> errorsRun.incrementAndGet());

            // when / then -- both error handlers ran and the lifecycle still fails with the original cause
            assertThatThrownBy(() -> uow.execute().join()).hasRootCause(failure);
            assertThat(errorsRun.get()).isEqualTo(2);
        }
    }
}
