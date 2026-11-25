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

package org.axonframework.eventsourcing.eventstore;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.test.appender.ListAppender;
import org.apache.logging.log4j.message.Message;
import org.axonframework.common.infra.MockComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.AppendObservation.Outcome;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.axonframework.common.util.AssertUtils.awaitSuccessfulCompletion;
import static org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils.aUnitOfWork;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Test class validating the {@link AppendObservingEventStorageEngine}.
 *
 * @author Alexey Afanasyev
 */
class AppendObservingEventStorageEngineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Tag CONFLICTING_TAG = Tag.of("courseId", "42");
    private static final AppendCondition CONFLICT_CHECKING_CONDITION =
            AppendCondition.withCriteria(EventCriteria.havingTags(CONFLICTING_TAG));

    private final RecordingAppendObserver observer = new RecordingAppendObserver();
    private final InMemoryEventStorageEngine inMemoryEngine = new InMemoryEventStorageEngine();
    private final AppendObservingEventStorageEngine testSubject =
            new AppendObservingEventStorageEngine(inMemoryEngine, observer);

    @Nested
    class WhenCommitted {

        @Test
        void reportsCommittedWithTheConsistencyMarkerOfTheAfterCommitStep() {
            // given
            AppendTransaction<?> transaction = append(testSubject, AppendCondition.none(), taggedEvent(0));

            // when
            ConsistencyMarker marker = commitAndAfterCommit(transaction);

            // then
            assertThat(observer.outcomes()).containsExactly(new Outcome.Committed(marker));
        }

        @Test
        void reportsTheConditionEventsAndContextOfTheAppend() {
            // given
            ProcessingContext context = new StubProcessingContext();
            List<TaggedEventMessage<?>> events = List.of(taggedEvent(0), taggedEvent(1));

            // when
            AppendTransaction<?> transaction =
                    awaitResult(testSubject.appendEvents(CONFLICT_CHECKING_CONDITION, context, events));
            commitAndAfterCommit(transaction);

            // then
            assertThat(observer.observations()).singleElement().satisfies(observation -> {
                assertThat(observation.condition()).isEqualTo(CONFLICT_CHECKING_CONDITION);
                assertThat(observation.events()).isEqualTo(events);
                assertThat(observation.context()).isSameAs(context);
            });
        }

        @Test
        void reportsTheEventsAsOfTheStartOfTheAppend() {
            // given
            List<TaggedEventMessage<?>> events = new CopyOnWriteArrayList<>(List.of(taggedEvent(0)));
            AppendTransaction<?> transaction = awaitResult(testSubject.appendEvents(AppendCondition.none(), null,
                                                                                    events));
            events.add(taggedEvent(1));

            // when
            commitAndAfterCommit(transaction);

            // then
            assertThat(observer.observations()).singleElement()
                                               .satisfies(observation -> assertThat(observation.events())
                                                       .hasSize(1));
        }

        @Test
        void reportsNothingBeforeTheAfterCommitStepCompletes() {
            // given
            AppendTransaction<?> transaction = append(testSubject, AppendCondition.none(), taggedEvent(0));

            // when
            joinAndUnwrap(uncheckedCommit(transaction));

            // then
            assertThat(observer.observations()).isEmpty();
        }

        @Test
        void reportsBeforeTheCallerOfTheAfterCommitStepContinues() {
            // given
            AppendTransaction<?> transaction = append(testSubject, AppendCondition.none(), taggedEvent(0));
            Object commitResult = awaitResult(uncheckedCommit(transaction));

            // when
            CompletableFuture<Integer> observationsSeenByCaller =
                    uncheckedAfterCommit(transaction, commitResult).thenApply(marker -> observer.observations()
                                                                                              .size());

            // then
            assertThat(joinAndUnwrap(observationsSeenByCaller)).isEqualTo(1);
        }

        @Test
        void relaysTheTransactionResultsOfTheDelegateUnchanged() {
            // given
            Object commitResult = new Object();
            ConsistencyMarker marker = new GlobalIndexConsistencyMarker(5);
            AppendTransaction<?> transaction =
                    appendThroughStub(StubAppendTransaction.succeedingWith(commitResult, marker));

            // when
            Object relayedCommitResult = awaitResult(uncheckedCommit(transaction));
            ConsistencyMarker relayedMarker = awaitResult(uncheckedAfterCommit(transaction, relayedCommitResult));

            // then
            assertThat(relayedCommitResult).isSameAs(commitResult);
            assertThat(relayedMarker).isSameAs(marker);
        }

        @Test
        void storesTheEventsInTheDelegate() {
            // given
            EventMessage event = EventTestUtils.createEvent(0);

            // when
            commitAndAfterCommit(append(testSubject, AppendCondition.none(),
                                        new GenericTaggedEventMessage<>(event, Set.of(CONFLICTING_TAG))));

            // then
            MessageStream.Entry<EventMessage> sourced =
                    inMemoryEngine.source(SourcingCondition.conditionFor(EventCriteria.havingTags(CONFLICTING_TAG)))
                                  .first()
                                  .asCompletableFuture()
                                  .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                  .join();
            assertThat(sourced).isNotNull();
            assertThat(sourced.message().identifier()).isEqualTo(event.identifier());
        }
    }

    @Nested
    class WhenRejected {

        @Test
        void reportsRejectedWhenTheDelegateRejectsEagerlyDuringAppend() {
            // given
            appendConflictingEvent();

            // when
            CompletableFuture<AppendTransaction<?>> result =
                    testSubject.appendEvents(CONFLICT_CHECKING_CONDITION, null, List.of(taggedEvent(1)));

            // then
            assertThat(failureOf(result)).isInstanceOf(AppendEventsTransactionRejectedException.class);
            assertThat(observer.outcomes()).singleElement()
                                           .isInstanceOfSatisfying(Outcome.Rejected.class, rejected -> assertThat(
                                                   rejected.exception().tags()
                                           ).contains(CONFLICTING_TAG));
        }

        @Test
        void reportsRejectedWhenTheDelegateRejectsDuringCommit() {
            // given an append that does not conflict yet when started
            AppendTransaction<?> transaction = append(testSubject, CONFLICT_CHECKING_CONDITION, taggedEvent(1));
            // and a conflicting event committed by someone else afterward
            appendConflictingEvent();

            // when
            CompletableFuture<Object> commit = uncheckedCommit(transaction);

            // then
            assertThatThrownBy(() -> joinAndUnwrap(commit))
                    .isInstanceOf(AppendEventsTransactionRejectedException.class);
            assertThat(observer.outcomes()).singleElement().isInstanceOf(Outcome.Rejected.class);
        }

        @Test
        void doesNotReportTheRollbackFollowingARejectedCommit() {
            // given
            AppendTransaction<?> transaction = append(testSubject, CONFLICT_CHECKING_CONDITION, taggedEvent(1));
            appendConflictingEvent();
            assertThat(uncheckedCommit(transaction)).failsWithin(TIMEOUT);

            // when
            transaction.rollback();

            // then
            assertThat(observer.outcomes()).singleElement().isInstanceOf(Outcome.Rejected.class);
        }

        @Test
        void recognizesARejectionWrappedInACompletionException() {
            // given
            AppendEventsTransactionRejectedException rejection =
                    AppendEventsTransactionRejectedException.conflictingEventsDetected(ConsistencyMarker.ORIGIN);
            CompletionException wrapped = new CompletionException(rejection);
            AppendObservingEventStorageEngine subject = observing(() -> CompletableFuture.failedFuture(wrapped));

            // when
            CompletableFuture<AppendTransaction<?>> result =
                    subject.appendEvents(CONFLICT_CHECKING_CONDITION, null, List.of(taggedEvent(0)));

            // then the failure is relayed unchanged
            assertThat(failureOf(result)).isSameAs(wrapped);
            // and the rejection is reported unwrapped
            assertThat(observer.outcomes()).containsExactly(new Outcome.Rejected(rejection));
        }

        @Test
        void relaysTheVeryRejectionInstanceOfTheDelegate() {
            // given
            AppendEventsTransactionRejectedException rejection =
                    AppendEventsTransactionRejectedException.conflictingEventsDetected(ConsistencyMarker.ORIGIN);
            AppendObservingEventStorageEngine subject = observing(() -> CompletableFuture.failedFuture(rejection));

            // when
            CompletableFuture<AppendTransaction<?>> result =
                    subject.appendEvents(CONFLICT_CHECKING_CONDITION, null, List.of(taggedEvent(0)));

            // then
            assertThat(failureOf(result)).isSameAs(rejection);
            assertThat(observer.outcomes()).containsExactly(new Outcome.Rejected(rejection));
        }

        @Test
        void reportsTheConflictingEventWhenDecoratingAConflictDiagnosingEngine() {
            // given
            AppendObservingEventStorageEngine subject = new AppendObservingEventStorageEngine(
                    new ConflictDiagnosingEventStorageEngine(inMemoryEngine), observer
            );
            EventMessage conflictingEvent = appendConflictingEvent();

            // when
            CompletableFuture<AppendTransaction<?>> result =
                    subject.appendEvents(CONFLICT_CHECKING_CONDITION, null, List.of(taggedEvent(1)));

            // then
            assertThat(result).failsWithin(TIMEOUT);
            assertThat(observer.outcomes()).singleElement().isInstanceOfSatisfying(
                    Outcome.Rejected.class,
                    rejected -> assertThat(rejected.exception().conflictingEvent())
                            .map(EventMessage::identifier)
                            .contains(conflictingEvent.identifier())
            );
        }
    }

    @Nested
    class WhenFailed {

        @Test
        void reportsFailedWhenTheAppendFutureFails() {
            // given
            IllegalStateException failure = new IllegalStateException("storage unavailable");
            AppendObservingEventStorageEngine subject = observing(() -> CompletableFuture.failedFuture(failure));

            // when
            CompletableFuture<AppendTransaction<?>> result =
                    subject.appendEvents(AppendCondition.none(), null, List.of(taggedEvent(0)));

            // then
            assertThat(failureOf(result)).isSameAs(failure);
            assertThat(observer.outcomes()).containsExactly(new Outcome.Failed(failure));
        }

        @Test
        void reportsTheUnwrappedCauseWhenTheAppendFutureFailsWithACompletionException() {
            // given
            IllegalStateException failure = new IllegalStateException("storage unavailable");
            CompletionException wrapped = new CompletionException(failure);
            AppendObservingEventStorageEngine subject = observing(() -> CompletableFuture.failedFuture(wrapped));

            // when
            CompletableFuture<AppendTransaction<?>> result =
                    subject.appendEvents(AppendCondition.none(), null, List.of(taggedEvent(0)));

            // then
            assertThat(failureOf(result)).isSameAs(wrapped);
            assertThat(observer.outcomes()).containsExactly(new Outcome.Failed(failure));
        }

        @Test
        void reportsAndRethrowsWhenTheDelegateThrowsSynchronously() {
            // given
            IllegalStateException failure = new IllegalStateException("storage unavailable");
            AppendObservingEventStorageEngine subject = observing(() -> {
                throw failure;
            });

            // when / then
            assertThatThrownBy(() -> subject.appendEvents(AppendCondition.none(), null, List.of(taggedEvent(0))))
                    .isSameAs(failure);
            assertThat(observer.outcomes()).containsExactly(new Outcome.Failed(failure));
        }

        @Test
        void reportsFailedWhenTheCommitFails() {
            // given
            IllegalStateException failure = new IllegalStateException("commit failed");
            AppendTransaction<?> transaction = appendThroughStub(StubAppendTransaction.committingWith(
                    () -> CompletableFuture.failedFuture(failure)
            ));

            // when
            CompletableFuture<Object> commit = uncheckedCommit(transaction);

            // then
            assertThat(failureOf(commit)).isSameAs(failure);
            assertThat(observer.outcomes()).containsExactly(new Outcome.Failed(failure));
        }

        @Test
        void reportsAndRethrowsWhenTheCommitThrowsSynchronously() {
            // given
            IllegalStateException failure = new IllegalStateException("commit failed");
            AppendTransaction<?> transaction = appendThroughStub(StubAppendTransaction.committingWith(() -> {
                throw failure;
            }));

            // when / then
            assertThatThrownBy(() -> uncheckedCommit(transaction)).isSameAs(failure);
            assertThat(observer.outcomes()).containsExactly(new Outcome.Failed(failure));
        }

        @Test
        void reportsFailedWhenTheAfterCommitStepFails() {
            // given
            IllegalStateException failure = new IllegalStateException("marker unavailable");
            AppendTransaction<?> transaction = appendThroughStub(StubAppendTransaction.failingAfterCommitWith(failure));
            Object commitResult = awaitResult(uncheckedCommit(transaction));

            // when
            CompletableFuture<ConsistencyMarker> afterCommit = uncheckedAfterCommit(transaction, commitResult);

            // then
            assertThat(failureOf(afterCommit)).isSameAs(failure);
            assertThat(observer.outcomes()).containsExactly(new Outcome.Failed(failure));
        }
    }

    @Nested
    class WhenRolledBack {

        @Test
        void reportsRolledBackWhenRolledBackBeforeCommit() {
            // given
            AppendTransaction<?> transaction = append(testSubject, AppendCondition.none(), taggedEvent(0));

            // when
            transaction.rollback();

            // then
            assertThat(observer.outcomes()).containsExactly(new Outcome.RolledBack());
        }

        @Test
        void rollsBackTheDelegateTransaction() {
            // given
            AtomicBoolean rolledBack = new AtomicBoolean(false);
            AppendTransaction<?> transaction =
                    appendThroughStub(StubAppendTransaction.rollingBackWith(() -> rolledBack.set(true)));

            // when
            transaction.rollback();

            // then
            assertThat(rolledBack.get()).isTrue();
        }

        @Test
        void doesNotReportTheRollbackOfACommittedAppend() {
            // given
            AppendTransaction<?> transaction = append(testSubject, AppendCondition.none(), taggedEvent(0));
            ConsistencyMarker marker = commitAndAfterCommit(transaction);

            // when
            transaction.rollback();

            // then
            assertThat(observer.outcomes()).containsExactly(new Outcome.Committed(marker));
        }

        @Test
        void doesNotReportARollbackFollowingASuccessfulCommit() {
            // given
            AppendTransaction<?> transaction = append(testSubject, AppendCondition.none(), taggedEvent(0));
            awaitResult(uncheckedCommit(transaction));

            // when
            transaction.rollback();

            // then
            assertThat(observer.observations()).isEmpty();
        }

        @Test
        void doesNotReportACommitAttemptedAfterTheRollback() {
            // given
            AppendTransaction<?> transaction = append(testSubject, AppendCondition.none(), taggedEvent(0));
            transaction.rollback();

            // when
            CompletableFuture<Object> commit = uncheckedCommit(transaction);

            // then
            assertThat(commit).failsWithin(TIMEOUT);
            assertThat(observer.outcomes()).containsExactly(new Outcome.RolledBack());
        }

        @Test
        void reportsAndRethrowsWhenTheRollbackFails() {
            // given
            IllegalStateException failure = new IllegalStateException("rollback failed");
            AppendTransaction<?> transaction = appendThroughStub(StubAppendTransaction.rollingBackWith(() -> {
                throw failure;
            }));

            // when / then
            assertThatThrownBy(transaction::rollback).isSameAs(failure);
            assertThat(observer.outcomes()).containsExactly(new Outcome.Failed(failure));
        }
    }

    @Nested
    class ObserverFailures {

        private ListAppender engineLog;

        @BeforeEach
        void attachAppender() {
            engineLog = new ListAppender("AppendObservingEventStorageEngineLog");
            engineLog.start();
            ((Logger) LogManager.getLogger(AppendObservingEventStorageEngine.class)).addAppender(engineLog);
        }

        @AfterEach
        void detachAppender() {
            ((Logger) LogManager.getLogger(AppendObservingEventStorageEngine.class)).removeAppender(engineLog);
            engineLog.stop();
        }

        @Test
        void committingIsUnaffectedByAFailingObserver() {
            // given
            AppendObservingEventStorageEngine subject = new AppendObservingEventStorageEngine(
                    inMemoryEngine, failingObserver()
            );
            AppendTransaction<?> transaction = append(subject, AppendCondition.none(), taggedEvent(0));

            // when
            ConsistencyMarker marker = commitAndAfterCommit(transaction);

            // then
            assertThat(marker).isNotEqualTo(ConsistencyMarker.ORIGIN);
        }

        @Test
        void aRejectionIsRelayedUnchangedDespiteAFailingObserver() {
            // given
            AppendEventsTransactionRejectedException rejection =
                    AppendEventsTransactionRejectedException.conflictingEventsDetected(ConsistencyMarker.ORIGIN);
            AppendObservingEventStorageEngine subject = new AppendObservingEventStorageEngine(
                    new FixedAppendEventStorageEngine(() -> CompletableFuture.failedFuture(rejection)),
                    failingObserver()
            );

            // when
            CompletableFuture<AppendTransaction<?>> result =
                    subject.appendEvents(CONFLICT_CHECKING_CONDITION, null, List.of(taggedEvent(0)));

            // then
            assertThat(failureOf(result)).isSameAs(rejection);
        }

        @Test
        void logsTheObserverFailureAsAWarning() {
            // given
            AppendObservingEventStorageEngine subject = new AppendObservingEventStorageEngine(
                    inMemoryEngine, failingObserver()
            );

            // when
            commitAndAfterCommit(append(subject, AppendCondition.none(), taggedEvent(0)));

            // then
            assertThat(engineLog.getEvents())
                    .extracting(LogEvent::getMessage)
                    .extracting(Message::getFormattedMessage)
                    .singleElement()
                    .asString()
                    .startsWith("AppendObserver [")
                    .endsWith("] failed; the append outcome is unaffected");
            assertThat(engineLog.getEvents())
                    .extracting(LogEvent::getThrown)
                    .singleElement()
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void completesTheUnitOfWorkDespiteAnObserverThrowingAnError() {
            // given
            EventStore eventStore = new StorageEngineBackedEventStore(
                    new AppendObservingEventStorageEngine(inMemoryEngine, erroneousObserver()),
                    new SimpleEventBus(),
                    event -> Set.of(CONFLICTING_TAG)
            );
            UnitOfWork unitOfWork = aUnitOfWork();
            unitOfWork.runOnInvocation(context -> eventStore.transaction(context)
                                                            .appendEvent(EventTestUtils.createEvent(0)));

            // when
            CompletableFuture<Void> result = unitOfWork.execute();

            // then
            assertThat(result).succeedsWithin(TIMEOUT);
        }

        @Test
        void logsAnObserverErrorAfterACommittedAppend() {
            // given
            AppendObservingEventStorageEngine subject = new AppendObservingEventStorageEngine(
                    inMemoryEngine, erroneousObserver()
            );
            AppendTransaction<?> transaction = append(subject, AppendCondition.none(), taggedEvent(0));

            // when
            ConsistencyMarker marker = commitAndAfterCommit(transaction);

            // then
            assertThat(marker).isNotEqualTo(ConsistencyMarker.ORIGIN);
            assertThat(engineLog.getEvents()).singleElement().satisfies(logged -> {
                assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
                assertThat(logged.getThrown()).isInstanceOf(AssertionError.class);
            });
        }

        @Test
        void logsAnObserverErrorAfterARolledBackAppend() {
            // given
            AppendObservingEventStorageEngine subject = new AppendObservingEventStorageEngine(
                    inMemoryEngine, erroneousObserver()
            );
            AppendTransaction<?> transaction = append(subject, AppendCondition.none(), taggedEvent(0));

            // when
            transaction.rollback();

            // then
            assertThat(engineLog.getEvents()).singleElement().satisfies(logged -> {
                assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
                assertThat(logged.getThrown()).isInstanceOf(AssertionError.class);
            });
        }

        @Test
        void relaysARejectionWithTheObserverErrorAsSuppressedException() {
            // given
            AppendEventsTransactionRejectedException rejection =
                    AppendEventsTransactionRejectedException.conflictingEventsDetected(ConsistencyMarker.ORIGIN);
            AppendObservingEventStorageEngine subject = new AppendObservingEventStorageEngine(
                    new FixedAppendEventStorageEngine(() -> CompletableFuture.failedFuture(rejection)),
                    erroneousObserver()
            );

            // when
            CompletableFuture<AppendTransaction<?>> result =
                    subject.appendEvents(CONFLICT_CHECKING_CONDITION, null, List.of(taggedEvent(0)));

            // then
            assertThat(failureOf(result)).isSameAs(rejection);
            assertThat(rejection.getSuppressed()).singleElement().isInstanceOf(AssertionError.class);
        }

        @Test
        void attachesTheObserverErrorToTheCauseOfAWrappedRejection() {
            // given
            AppendEventsTransactionRejectedException rejection =
                    AppendEventsTransactionRejectedException.conflictingEventsDetected(ConsistencyMarker.ORIGIN);
            AppendObservingEventStorageEngine subject = new AppendObservingEventStorageEngine(
                    new FixedAppendEventStorageEngine(
                            () -> CompletableFuture.completedFuture(null).thenApply(ignored -> {
                                throw rejection;
                            })
                    ),
                    erroneousObserver()
            );

            // when
            CompletableFuture<AppendTransaction<?>> result =
                    subject.appendEvents(CONFLICT_CHECKING_CONDITION, null, List.of(taggedEvent(0)));

            // then the caller sees the rejection instance, carrying the observer error
            assertThat(failureOf(result)).isInstanceOf(CompletionException.class).cause().isSameAs(rejection);
            assertThat(rejection.getSuppressed()).singleElement().isInstanceOf(AssertionError.class);
            // and the observer error is logged
            assertThat(engineLog.getEvents()).singleElement().satisfies(logged -> {
                assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
                assertThat(logged.getThrown()).isInstanceOf(AssertionError.class);
            });
        }

        @Test
        void rethrowsASynchronousFailureWithTheObserverErrorAsSuppressedException() {
            // given
            IllegalStateException failure = new IllegalStateException("storage unavailable");
            AppendObservingEventStorageEngine subject = new AppendObservingEventStorageEngine(
                    new FixedAppendEventStorageEngine(() -> {
                        throw failure;
                    }),
                    erroneousObserver()
            );

            // when / then
            assertThatThrownBy(() -> subject.appendEvents(AppendCondition.none(), null, List.of(taggedEvent(0))))
                    .isSameAs(failure);
            assertThat(failure.getSuppressed()).singleElement().isInstanceOf(AssertionError.class);
        }

        private static AppendObserver failingObserver() {
            return observation -> {
                throw new IllegalStateException("observer failed");
            };
        }

        private static AppendObserver erroneousObserver() {
            return observation -> {
                throw new AssertionError("observer error");
            };
        }
    }

    @Nested
    class Delegation {

        private final InMemoryEventStorageEngine delegate = spy(new InMemoryEventStorageEngine());
        private final AppendObservingEventStorageEngine subject =
                new AppendObservingEventStorageEngine(delegate, observer);

        @Test
        void appendsTheGivenConditionContextAndEventsThroughTheDelegate() {
            // given
            ProcessingContext context = new StubProcessingContext();
            List<TaggedEventMessage<?>> events = List.of(taggedEvent(0));

            // when
            joinAndUnwrap(subject.appendEvents(CONFLICT_CHECKING_CONDITION, context, events));

            // then
            verify(delegate).appendEvents(CONFLICT_CHECKING_CONDITION, context, events);
        }

        @Test
        void sourcesThroughTheDelegate() {
            // given
            SourcingCondition condition = SourcingCondition.conditionFor(EventCriteria.havingTags(CONFLICTING_TAG));
            ProcessingContext context = new StubProcessingContext();

            // when
            subject.source(condition, context);

            // then
            verify(delegate).source(condition, context);
        }

        @Test
        void streamsThroughTheDelegate() {
            // given
            StreamingCondition condition = StreamingCondition.startingFrom(new GlobalSequenceTrackingToken(0));

            // when
            subject.stream(condition).close();

            // then
            verify(delegate).stream(condition);
        }

        @Test
        void createsTheFirstTokenThroughTheDelegate() {
            // when
            subject.firstToken();

            // then
            verify(delegate).firstToken();
        }

        @Test
        void createsTheLatestTokenThroughTheDelegate() {
            // when
            subject.latestToken();

            // then
            verify(delegate).latestToken();
        }

        @Test
        void createsATokenAtAnInstantThroughTheDelegate() {
            // given
            Instant at = Instant.now();

            // when
            subject.tokenAt(at);

            // then
            verify(delegate).tokenAt(at);
        }

        @Test
        void exposesTheDelegateAsDecoratedDelegate() {
            assertThat(subject.decoratedDelegate()).isSameAs(delegate);
        }

        @Test
        void describesTheDelegateAndTheObserver() {
            // given
            MockComponentDescriptor descriptor = new MockComponentDescriptor();

            // when
            subject.describeTo(descriptor);

            // then
            assertThat(descriptor.getDescribedProperties())
                    .containsEntry("delegate", delegate)
                    .containsEntry("observer", observer);
        }

        @Test
        void rejectsANullDelegate() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new AppendObservingEventStorageEngine(null, observer))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void rejectsANullObserver() {
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new AppendObservingEventStorageEngine(delegate, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class WithinUnitOfWork {

        private final EventStore eventStore = new StorageEngineBackedEventStore(
                testSubject, new SimpleEventBus(), event -> Set.of(CONFLICTING_TAG)
        );

        @Test
        void reportsCommittedWithTheProcessingContextOfTheUnitOfWork() {
            // given
            AtomicReference<@Nullable ProcessingContext> appendingContext = new AtomicReference<>();
            UnitOfWork unitOfWork = aUnitOfWork();
            unitOfWork.runOnInvocation(context -> {
                appendingContext.set(context);
                eventStore.transaction(context).appendEvent(EventTestUtils.createEvent(0));
            });

            // when
            awaitSuccessfulCompletion(unitOfWork.execute());

            // then
            assertThat(observer.observations()).singleElement().satisfies(observation -> {
                assertThat(observation.outcome()).isInstanceOf(Outcome.Committed.class);
                assertThat(observation.context()).isSameAs(appendingContext.get());
                assertThat(observation.events()).hasSize(1);
            });
        }

        @Test
        void reportsRejectedWhenAConflictingEventWasAppendedSinceSourcing() {
            // given
            UnitOfWork unitOfWork = aUnitOfWork();
            unitOfWork.runOnInvocation(context -> {
                EventStoreTransaction transaction = eventStore.transaction(context);
                transaction.source(SourcingCondition.conditionFor(EventCriteria.havingTags(CONFLICTING_TAG)))
                           .first()
                           .asCompletableFuture()
                           .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                           .join();
                appendConflictingEvent();
                transaction.appendEvent(EventTestUtils.createEvent(1));
            });

            // when
            CompletableFuture<Void> result = unitOfWork.execute();

            // then
            assertThat(result).failsWithin(TIMEOUT);
            assertThat(observer.outcomes()).singleElement().isInstanceOf(Outcome.Rejected.class);
        }

        @Test
        void reportsRolledBackWhenTheUnitOfWorkFailsBeforeCommitting() {
            // given
            UnitOfWork unitOfWork = aUnitOfWork();
            unitOfWork.runOnInvocation(context -> eventStore.transaction(context)
                                                            .appendEvent(EventTestUtils.createEvent(0)));
            ProcessingLifecycle.Phase beforeCommit =
                    () -> ProcessingLifecycle.DefaultPhases.COMMIT.order() - 1;
            unitOfWork.on(beforeCommit, context -> CompletableFuture.failedFuture(
                    new IllegalStateException("failure after the events were handed to the engine")
            ));

            // when
            CompletableFuture<Void> result = unitOfWork.execute();

            // then
            assertThat(result).failsWithin(TIMEOUT);
            assertThat(observer.outcomes()).containsExactly(new Outcome.RolledBack());
        }

        @Test
        void reportsNoRollbackWhenAnotherCommitActionFailsAfterTheAppendCommitted() {
            // given
            UnitOfWork unitOfWork = aUnitOfWork();
            unitOfWork.runOnInvocation(context -> eventStore.transaction(context)
                                                            .appendEvent(EventTestUtils.createEvent(0)));
            unitOfWork.onCommit(context -> CompletableFuture.failedFuture(
                    new IllegalStateException("another commit action failed")
            ));

            // when
            CompletableFuture<Void> result = unitOfWork.execute();

            // then the unit of work fails and rolls back, although the events were committed
            assertThat(result).failsWithin(TIMEOUT);
            MessageStream.Entry<EventMessage> stored =
                    inMemoryEngine.source(SourcingCondition.conditionFor(EventCriteria.havingTags(CONFLICTING_TAG)))
                                  .first()
                                  .asCompletableFuture()
                                  .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                  .join();
            assertThat(stored).isNotNull();
            // and so the rollback is not reported
            assertThat(observer.observations()).isEmpty();
        }
    }

    private AppendObservingEventStorageEngine observing(
            Supplier<CompletableFuture<AppendTransaction<?>>> appendResult
    ) {
        return new AppendObservingEventStorageEngine(new FixedAppendEventStorageEngine(appendResult), observer);
    }

    private AppendTransaction<?> appendThroughStub(StubAppendTransaction delegateTransaction) {
        return append(observing(() -> CompletableFuture.completedFuture(delegateTransaction)),
                      AppendCondition.none(), taggedEvent(0));
    }

    private static AppendTransaction<?> append(EventStorageEngine engine,
                                               AppendCondition condition,
                                               TaggedEventMessage<?> event) {
        return awaitResult(engine.appendEvents(condition, null, List.of(event)));
    }

    private EventMessage appendConflictingEvent() {
        EventMessage event = EventTestUtils.createEvent(0);
        commitAndAfterCommit(append(inMemoryEngine, AppendCondition.none(),
                                    new GenericTaggedEventMessage<>(event, Set.of(CONFLICTING_TAG))));
        return event;
    }

    private static TaggedEventMessage<?> taggedEvent(int sequence) {
        return new GenericTaggedEventMessage<>(EventTestUtils.createEvent(sequence), Set.of());
    }

    private static ConsistencyMarker commitAndAfterCommit(AppendTransaction<?> transaction) {
        Object commitResult = awaitResult(uncheckedCommit(transaction));
        return awaitResult(uncheckedAfterCommit(transaction, commitResult));
    }

    /**
     * Waits for the given {@code future} to complete and returns its result, which must not be {@code null}. A failure
     * of the future is rethrown as is, without a {@link CompletionException} wrapper.
     */
    private static <T> T awaitResult(CompletableFuture<T> future) {
        return Objects.requireNonNull(joinAndUnwrap(future, TIMEOUT), "The future completed with a null result.");
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Object> uncheckedCommit(AppendTransaction<?> transaction) {
        return ((AppendTransaction<Object>) transaction).commit();
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<ConsistencyMarker> uncheckedAfterCommit(AppendTransaction<?> transaction,
                                                                              Object commitResult) {
        return ((AppendTransaction<Object>) transaction).afterCommit(commitResult);
    }

    /**
     * Returns the failure the given {@code future} completed with, as handed to the callbacks of the future.
     */
    private static Throwable failureOf(CompletableFuture<?> future) {
        return future.handle((result, failure) -> failure)
                     .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                     .join();
    }

    /**
     * An {@link AppendObserver} recording every observation it receives.
     */
    private static final class RecordingAppendObserver implements AppendObserver {

        private final List<AppendObservation> observations = new CopyOnWriteArrayList<>();

        @Override
        public void observe(AppendObservation observation) {
            observations.add(observation);
        }

        private List<AppendObservation> observations() {
            return observations;
        }

        private List<Outcome> outcomes() {
            return observations.stream().map(AppendObservation::outcome).toList();
        }
    }

    /**
     * An {@link InMemoryEventStorageEngine} whose appends result in the outcome of the given {@code appendResult}.
     */
    private static final class FixedAppendEventStorageEngine extends InMemoryEventStorageEngine {

        private final Supplier<CompletableFuture<AppendTransaction<?>>> appendResult;

        private FixedAppendEventStorageEngine(Supplier<CompletableFuture<AppendTransaction<?>>> appendResult) {
            this.appendResult = appendResult;
        }

        @Override
        public CompletableFuture<AppendTransaction<?>> appendEvents(AppendCondition condition,
                                                                    @Nullable ProcessingContext processingContext,
                                                                    List<TaggedEventMessage<?>> events) {
            return appendResult.get();
        }
    }

    /**
     * An {@link AppendTransaction} whose operations behave as the given functions.
     */
    private record StubAppendTransaction(
            Supplier<CompletableFuture<Object>> onCommit,
            Function<Object, CompletableFuture<ConsistencyMarker>> onAfterCommit,
            Runnable onRollback
    ) implements AppendTransaction<Object> {

        private static final Runnable NO_OP = () -> {
        };

        private static StubAppendTransaction succeedingWith(Object commitResult, ConsistencyMarker marker) {
            return new StubAppendTransaction(() -> CompletableFuture.completedFuture(commitResult),
                                             result -> CompletableFuture.completedFuture(marker),
                                             NO_OP);
        }

        private static StubAppendTransaction committingWith(Supplier<CompletableFuture<Object>> onCommit) {
            return new StubAppendTransaction(onCommit,
                                             result -> CompletableFuture.completedFuture(ConsistencyMarker.ORIGIN),
                                             NO_OP);
        }

        private static StubAppendTransaction failingAfterCommitWith(Throwable failure) {
            return new StubAppendTransaction(() -> CompletableFuture.completedFuture("committed"),
                                             result -> CompletableFuture.failedFuture(failure),
                                             NO_OP);
        }

        private static StubAppendTransaction rollingBackWith(Runnable onRollback) {
            return new StubAppendTransaction(() -> CompletableFuture.completedFuture("committed"),
                                             result -> CompletableFuture.completedFuture(ConsistencyMarker.ORIGIN),
                                             onRollback);
        }

        @Override
        public CompletableFuture<Object> commit() {
            return onCommit.get();
        }

        @Override
        public void rollback() {
            onRollback.run();
        }

        @Override
        public CompletableFuture<ConsistencyMarker> afterCommit(Object commitResult) {
            return onAfterCommit.apply(commitResult);
        }
    }
}
