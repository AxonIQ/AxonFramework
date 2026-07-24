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

package org.axonframework.messaging.tracing;

import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStreamTestUtils;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the termination semantics of {@link Span}'s structured operations -- when exactly a branch-scoped or
 * lifecycle-bound span ends, how failures are recorded, and how the context-level leak backstops behave -- directly
 * against the default implementations, independent of any tracing binding.
 */
class SpanStructuredOperationsTest {

    private static final String OPERATION = "operation";

    private TestSpanFactory spanFactory;

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
    }

    private Span internalSpan() {
        return spanFactory.createInternalSpan(OPERATION, null);
    }

    private void inUnitOfWork(Consumer<ProcessingContext> action) {
        UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();
        unitOfWork.onInvocation(context -> {
            action.accept(context);
            return CompletableFuture.completedFuture(null);
        });
        try {
            unitOfWork.execute().get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    class BranchAsync {

        @Test
        void escapedBranchFallsBackToTheLifecycleScopeAfterTheOperationCompletes() {
            // given a lifecycle span and a repository-like branch that registers deferred work through its context
            inUnitOfWork(context -> {
                spanFactory.createInternalSpan("lifecycle", context).coverLifecycle(context);

                spanFactory.createInternalSpan("repository", context)
                           .branchAsync(context, branch -> {
                               branch.onPrepareCommit(deferredContext -> {
                                   spanFactory.createInternalSpan("deferred", deferredContext).start().close();
                                   return CompletableFuture.completedFuture(null);
                               });
                               return CompletableFuture.completedFuture("loaded");
                           })
                           .join();
            });

            // when PREPARE_COMMIT runs after repository completion, the closed repository scope is skipped
            spanFactory.verifySpanHasParent("repository", "lifecycle");
            spanFactory.verifySpanHasParent("deferred", "lifecycle");
        }

        @Test
        void endsTheSpanOnlyWhenTheOperationsFutureCompletes() {
            // given an operation whose future completes later
            CompletableFuture<String> gate = new CompletableFuture<>();

            // when the operation's synchronous frame has returned but the future is still pending
            CompletableFuture<String> result = internalSpan().branchAsync(null, ignored -> gate);

            // then the span is still open -- it ends with the future, not with the frame
            spanFactory.verifySpanActive(OPERATION);
            gate.complete("done");
            spanFactory.verifySpanCompleted(OPERATION);
            assertThat(result).isCompletedWithValue("done");
        }

        @Test
        void recordsTheFuturesFailureOnTheSpanAndStillEndsIt() {
            // given an operation whose future fails later
            CompletableFuture<String> gate = new CompletableFuture<>();
            CompletableFuture<String> result = internalSpan().branchAsync(null, ignored -> gate);

            // when the future completes exceptionally
            gate.completeExceptionally(new IllegalStateException("boom"));

            // then the failure is recorded and the span ends
            spanFactory.verifySpanHasException(OPERATION, IllegalStateException.class);
            spanFactory.verifySpanCompleted(OPERATION);
            assertThat(result).isCompletedExceptionally();
        }

        @Test
        void recordsASynchronousThrowEndsTheSpanAndRethrows() {
            // when the operation itself throws before producing a future
            assertThatThrownBy(() -> internalSpan().branchAsync(null, ignored -> {
                throw new IllegalStateException("boom");
            }))
                    // then the throwable propagates unchanged
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");

            // and the failure is recorded and the span ends
            spanFactory.verifySpanHasException(OPERATION, IllegalStateException.class);
            spanFactory.verifySpanCompleted(OPERATION);
        }

        @Test
        void convertsANullFutureIntoARecordedFailureAndEndsTheSpan() {
            // when a misbehaving operation returns null instead of a future
            assertThatThrownBy(() -> internalSpan().branchAsync(null, ignored -> null))
                    // then the contract violation surfaces immediately instead of leaking the span
                    .isInstanceOf(NullPointerException.class);

            spanFactory.verifySpanHasException(OPERATION, NullPointerException.class);
            spanFactory.verifySpanCompleted(OPERATION);
        }

        @Test
        void closesTheSpanAtContextCompletionWhenTheFutureNeverCompletes() {
            // given a context-carrying branchAsync whose future is abandoned (never completes)
            inUnitOfWork(context -> internalSpan().branchAsync(context, ignored -> new CompletableFuture<>()));

            // then the doFinally leak backstop ends the span when the context completes
            spanFactory.verifySpanCompleted(OPERATION);
        }

        @Test
        void closedNestedBranchesFallBackToTheNearestOpenScope() {
            // given a root scope and two nested context branches
            ProcessingContext rootContext = new StubProcessingContext();
            SpanScope rootScope = spanFactory.createInternalSpan("root", null).start();
            rootContext.putResource(SpanScope.RESOURCE_KEY, rootScope);
            SpanScope outerScope = spanFactory.createInternalSpan("outer", rootContext).start();
            ProcessingContext outerContext = SpanScope.addToContext(rootContext, outerScope);
            SpanScope innerScope = spanFactory.createInternalSpan("inner", outerContext).start();
            ProcessingContext innerContext = SpanScope.addToContext(outerContext, innerScope);

            // then closing the inner branch reveals the still-open outer scope
            innerScope.close();
            spanFactory.createInternalSpan("after-inner", innerContext).start().close();
            spanFactory.verifySpanHasParent("after-inner", "outer");

            // when the outer branch closes, the same retained context resolves the root scope
            outerScope.close();
            spanFactory.createInternalSpan("after-outer", innerContext).start().close();
            spanFactory.verifySpanHasParent("after-outer", "root");

            // and after every scope closes, the retained context has no active parent
            rootScope.close();
            spanFactory.createInternalSpan("after-root", innerContext).start().close();
            spanFactory.verifySpanHasNoParent("after-root");
        }

        @Test
        void escapedBranchFallsBackToTheScopeInheritedAtBranchCreationNotTheRootsReplacement() {
            // given a lifecycle scope on the root and a branch created -- and closed -- while it was active
            inUnitOfWork(context -> {
                SpanScope firstLifecycle =
                        spanFactory.createInternalSpan("first-lifecycle", context).coverLifecycle(context);
                SpanScope branchScope = spanFactory.createInternalSpan("branch", context).start();
                ProcessingContext branchContext = SpanScope.addToContext(context, branchScope);
                branchScope.close();

                // when a second lifecycle scope replaces the root's carrier (last-writer-wins)
                spanFactory.createInternalSpan("second-lifecycle", context).coverLifecycle(context);

                // then the escaped branch resolves the scope inherited at branch-creation time, not the replacement
                spanFactory.createInternalSpan("after-branch", branchContext).start().close();

                // and once the inherited scope closes as well, resolution yields no parent rather than the replacement
                firstLifecycle.close();
                spanFactory.createInternalSpan("after-first-lifecycle", branchContext).start().close();
            });

            spanFactory.verifySpanHasParent("after-branch", "first-lifecycle");
            spanFactory.verifySpanHasNoParent("after-first-lifecycle");
        }
    }

    @Nested
    class ScopeOperations {

        @Test
        void withinRunnableExecutesTheOperationInsideTheScopeWithoutEndingTheSpan() {
            // given a started branch scope
            SpanScope scope = internalSpan().start();

            // when a void operation runs within it
            AtomicBoolean executed = new AtomicBoolean(false);
            scope.within(() -> executed.set(true));

            // then the operation ran through the scope's extension point and the span is still open
            assertThat(executed).isTrue();
            spanFactory.verifyScopeEnteredAtLeast(OPERATION, 1);
            spanFactory.verifySpanActive(OPERATION);
            scope.close();
        }
    }

    @Nested
    class BranchStream {

        @Test
        void endsTheSpanOnTheResultStreamsTerminationNotOnItsConstruction() {
            // given a stream whose single entry becomes available later
            CompletableFuture<EventMessage> gate = new CompletableFuture<>();
            MessageStream<EventMessage> stream =
                    internalSpan().branchStream(null, ignored -> MessageStream.fromFuture(gate));

            // then constructing the stream does not end the span
            spanFactory.verifySpanActive(OPERATION);

            // when the entry arrives and the stream is drained to its terminal
            gate.complete(EventTestUtils.createEvent(0));
            assertThat(stream.first().asCompletableFuture().orTimeout(2, TimeUnit.SECONDS).join()).isNotNull();

            // then the span ends with the stream
            spanFactory.verifySpanCompleted(OPERATION);
        }

        @Test
        void recordsAStreamFailureOnTheSpanAndEndsIt() {
            // when the operation produces an already-failed stream
            MessageStream<EventMessage> stream =
                    internalSpan().branchStream(
                            null,
                            ignored -> MessageStream.failed(new IllegalStateException("boom"))
                    );

            // then the failure is recorded, the span ends, and the stream still carries the error
            spanFactory.verifySpanHasException(OPERATION, IllegalStateException.class);
            spanFactory.verifySpanCompleted(OPERATION);
            MessageStreamTestUtils.assertCompletedExceptionally(stream, IllegalStateException.class, "boom");
        }

        @Test
        void reEntersTheSpansScopeAroundEveryPull() {
            // given a branch-scoped stream of two entries
            MessageStream<EventMessage> stream = internalSpan().branchStream(
                    null,
                    ignored -> MessageStream.fromItems(EventTestUtils.createEvent(0), EventTestUtils.createEvent(1))
            );

            // when both entries are pulled
            assertThat(stream.next()).isPresent();
            assertThat(stream.next()).isPresent();

            // then the scope was entered for the composition window plus once per pull
            spanFactory.verifyScopeEnteredAtLeast(OPERATION, 3);
        }

        @Test
        void closesTheSpanAtContextCompletionWhenTheStreamIsAbandoned() {
            // given a context-carrying branchStream whose stream is dropped without ever being consumed
            inUnitOfWork(context -> internalSpan().branchStream(
                    context, ignored -> MessageStream.fromFuture(new CompletableFuture<>())));

            // then the doFinally leak backstop ends the span when the context completes
            spanFactory.verifySpanCompleted(OPERATION);
        }
    }

    @Nested
    class LifecycleOperations {

        @Test
        void coverLifecycleMakesTheSpanTheContextsActiveScopeAndEndsItAtContextCompletion() {
            // given a span covering the context's lifecycle
            inUnitOfWork(context -> {
                spanFactory.createInternalSpan("covering", context).coverLifecycle(context);

                // when another span is created with the same context mid-lifecycle
                spanFactory.createInternalSpan("inner", context);

                // then the covering span is still open and is the parent the context hands out
                spanFactory.verifySpanActive("covering");
            });

            // then the covering span ends with the context, and the mid-lifecycle child parented under it
            spanFactory.verifySpanCompleted("covering");
            spanFactory.verifySpanHasParent("inner", "covering");
        }

        @Test
        void coverLifecycleRecordsAProcessingErrorOnTheSpan() {
            // given a unit of work that fails after the span covers its lifecycle
            UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();
            unitOfWork.onInvocation(context -> {
                spanFactory.createInternalSpan("covering", context).coverLifecycle(context);
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            });

            // when the unit of work is executed
            assertThatThrownBy(() -> unitOfWork.execute().get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);

            // then the processing error is recorded on the span and the span still ends
            spanFactory.verifySpanHasException("covering", IllegalStateException.class);
            spanFactory.verifySpanCompleted("covering");
        }
    }
}
