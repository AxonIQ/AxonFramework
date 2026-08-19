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

package org.axonframework.eventsourcing.eventstore.tracing;

import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.eventsourcing.eventstore.GenericTaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.TaggedEventMessage;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TracingEventStorageEngineTest {

    private static final String APPEND_SPAN = "EventStorageEngine.appendTransaction";

    private TestSpanFactory spanFactory;
    private RecordingEventStorageEngine delegate;
    private TracingEventStorageEngine testSubject;
    private RecordingAppendTransaction transaction;
    private ProcessingContext context;

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        transaction = new RecordingAppendTransaction();
        delegate = new RecordingEventStorageEngine(ignored -> CompletableFuture.completedFuture(transaction));
        testSubject = new TracingEventStorageEngine(delegate, spanFactory);
        context = null;
    }

    @Test
    void keepsTheSpanActiveAcrossTheCompleteSuccessfulAppendTransaction() {
        // given
        context = new StubProcessingContext();

        // when
        AppendTransaction<?> result = joinAndUnwrap(append());

        // then
        spanFactory.verifySpanActive(APPEND_SPAN);
        spanFactory.verifySpanHasType(APPEND_SPAN, TestSpanType.INTERNAL);
        spanFactory.verifyContextCarriesScopeOf(APPEND_SPAN, delegate.receivedContext);

        // when
        Object commitResult = joinAndUnwrap(result.commit());

        // then
        assertThat(commitResult).isEqualTo("commit-result");
        spanFactory.verifySpanActive(APPEND_SPAN);

        // when
        joinAndUnwrap(afterCommit(result, commitResult));

        // then
        spanFactory.verifySpanCompleted(APPEND_SPAN);
        spanFactory.verifyScopeEnteredAtLeast(APPEND_SPAN, 3);
    }

    @Test
    void rollsBackWithinTheSpanAndThenEndsIt() {
        // given
        AppendTransaction<?> result = joinAndUnwrap(append());

        // when
        result.rollback();

        // then
        assertThat(transaction.rolledBack).isTrue();
        spanFactory.verifySpanCompleted(APPEND_SPAN);
        spanFactory.verifyScopeEnteredAtLeast(APPEND_SPAN, 2);
    }

    @Test
    void closesTheSpanAtContextCompletionWhenTheTransactionIsAbandoned() {
        // given an append inside a unit of work whose transaction never reaches a terminal operation
        UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();
        unitOfWork.onInvocation(processingContext -> {
            context = processingContext;
            return append();
        });

        // when the unit of work completes without a commit or rollback
        joinAndUnwrap(unitOfWork.execute());

        // then the context-completion leak backstop ends the span
        spanFactory.verifySpanCompleted(APPEND_SPAN);
    }

    @Nested
    class AppendFailures {

        @Test
        void recordsAndEndsOnASynchronousFailure() {
            // given
            delegate.append = ignored -> {
                throw new IllegalStateException("append failed");
            };

            // when / then
            assertThatThrownBy(() -> append())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("append failed");
            verifyFailureEnded();
        }

        @Test
        void recordsAndEndsOnAnAsynchronousFailure() {
            // given
            delegate.append = ignored -> CompletableFuture.failedFuture(
                    new IllegalStateException("append failed")
            );

            // when / then
            assertThatThrownBy(() -> joinAndUnwrap(append()))
                    .isInstanceOf(IllegalStateException.class);
            verifyFailureEnded();
        }

        @Test
        void recordsAndEndsWhenTheDelegateReturnsANullTransaction() {
            // given
            delegate.append = ignored -> CompletableFuture.completedFuture(null);

            // when / then
            assertThatThrownBy(() -> joinAndUnwrap(append()))
                    .isInstanceOf(NullPointerException.class);
            spanFactory.verifySpanHasException(APPEND_SPAN, NullPointerException.class);
            spanFactory.verifySpanCompleted(APPEND_SPAN);
        }
    }

    @Nested
    class CommitFailures {

        @Test
        void recordsAndEndsOnASynchronousFailure() {
            // given
            transaction.commit = () -> {
                throw new IllegalStateException("commit failed");
            };
            AppendTransaction<?> result = joinAndUnwrap(append());

            // when / then
            assertThatThrownBy(result::commit)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("commit failed");
            verifyFailureEnded();
        }

        @Test
        void recordsAndEndsOnAnAsynchronousFailure() {
            // given
            transaction.commit = () -> CompletableFuture.failedFuture(
                    new IllegalStateException("commit failed")
            );
            AppendTransaction<?> result = joinAndUnwrap(append());

            // when / then
            assertThatThrownBy(() -> joinAndUnwrap(result.commit()))
                    .isInstanceOf(IllegalStateException.class);
            verifyFailureEnded();
        }
    }

    @Nested
    class AfterCommitFailures {

        @Test
        void recordsAndEndsOnASynchronousFailure() {
            // given
            transaction.afterCommit = ignored -> {
                throw new IllegalStateException("after commit failed");
            };
            AppendTransaction<?> result = joinAndUnwrap(append());
            Object commitResult = joinAndUnwrap(result.commit());

            // when / then
            assertThatThrownBy(() -> afterCommit(result, commitResult))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("after commit failed");
            verifyFailureEnded();
        }

        @Test
        void recordsAndEndsOnAnAsynchronousFailure() {
            // given
            transaction.afterCommit = ignored -> CompletableFuture.failedFuture(
                    new IllegalStateException("after commit failed")
            );
            AppendTransaction<?> result = joinAndUnwrap(append());
            Object commitResult = joinAndUnwrap(result.commit());

            // when / then
            assertThatThrownBy(() -> joinAndUnwrap(afterCommit(result, commitResult)))
                    .isInstanceOf(IllegalStateException.class);
            verifyFailureEnded();
        }
    }

    @Test
    void recordsRollbackFailureAndStillEndsTheSpan() {
        // given
        transaction.rollbackFailure = new IllegalStateException("rollback failed");
        AppendTransaction<?> result = joinAndUnwrap(append());

        // when / then
        assertThatThrownBy(result::rollback)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback failed");
        verifyFailureEnded();
    }

    private CompletableFuture<AppendTransaction<?>> append() {
        return testSubject.appendEvents(
                AppendCondition.none(),
                context,
                List.of(new GenericTaggedEventMessage<>(EventTestUtils.createEvent(0), Set.of()))
        );
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<ConsistencyMarker> afterCommit(AppendTransaction<?> transaction,
                                                                     Object commitResult) {
        return ((AppendTransaction<Object>) transaction).afterCommit(commitResult);
    }

    private void verifyFailureEnded() {
        spanFactory.verifySpanHasException(APPEND_SPAN, IllegalStateException.class);
        spanFactory.verifySpanCompleted(APPEND_SPAN);
    }

    private static class RecordingEventStorageEngine extends InMemoryEventStorageEngine {

        private Function<ProcessingContext, CompletableFuture<AppendTransaction<?>>> append;
        private ProcessingContext receivedContext;

        private RecordingEventStorageEngine(
                Function<ProcessingContext, CompletableFuture<AppendTransaction<?>>> append
        ) {
            this.append = append;
        }

        @Override
        public CompletableFuture<AppendTransaction<?>> appendEvents(AppendCondition condition,
                                                                     ProcessingContext context,
                                                                     List<TaggedEventMessage<?>> events) {
            receivedContext = context;
            return append.apply(context);
        }
    }

    private static class RecordingAppendTransaction implements AppendTransaction<String> {

        private java.util.function.Supplier<CompletableFuture<String>> commit =
                () -> CompletableFuture.completedFuture("commit-result");
        private Function<String, CompletableFuture<ConsistencyMarker>> afterCommit =
                ignored -> CompletableFuture.completedFuture(ConsistencyMarker.ORIGIN);
        private RuntimeException rollbackFailure;
        private boolean rolledBack;

        @Override
        public CompletableFuture<String> commit() {
            return commit.get();
        }

        @Override
        public void rollback() {
            rolledBack = true;
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
        }

        @Override
        public CompletableFuture<ConsistencyMarker> afterCommit(String commitResult) {
            return afterCommit.apply(commitResult);
        }
    }
}
