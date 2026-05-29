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

import org.axonframework.eventsourcing.eventstore.EventStorageEngine.AppendTransaction;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.junit.jupiter.api.*;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.util.AssertUtils.awaitExceptionalCompletion;
import static org.axonframework.common.util.AssertUtils.awaitSuccessfulCompletion;
import static org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils.aUnitOfWork;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link DefaultEventStoreTransaction}.
 *
 * @author Steven van Beelen
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class DefaultEventStoreTransactionTest {

    private static final String TEST_AGGREGATE_ID = "someId";
    private static final Tag AGGREGATE_ID_TAG = new Tag("aggregateIdentifier", TEST_AGGREGATE_ID);
    private static final EventCriteria TEST_AGGREGATE_CRITERIA =
            EventCriteria.havingTags(AGGREGATE_ID_TAG);
    private final Context.ResourceKey<EventStoreTransaction> testEventStoreTransactionKey =
            Context.ResourceKey.withLabel("eventStoreTransaction");
    private final ProcessingContext processingContext = mock(ProcessingContext.class);
    private final InMemoryEventStorageEngine eventStorageEngine = new InMemoryEventStorageEngine();

    @Nested
    class AppendEvent {

        @Test
        void sourcingConditionIsMappedToAppendCondition() {
            // given
            var eventCriteria = TEST_AGGREGATE_CRITERIA;
            var event1 = eventMessage(0);
            var event2 = eventMessage(1);
            var event3 = eventMessage(2);
            var sourcingCondition = SourcingCondition.conditionFor(eventCriteria);

            // when
            var beforeCommitEvents = new AtomicReference<MessageStream<? extends EventMessage>>();
            var afterCommitEvents = new AtomicReference<MessageStream<? extends EventMessage>>();
            var consistencyMarker = new AtomicReference<ConsistencyMarker>();
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                   beforeCommitEvents.set(transaction.source(sourcingCondition));
               })
               .runOnPostInvocation(context -> {
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                   transaction.appendEvent(event1);
                   transaction.appendEvent(event2);
                   transaction.appendEvent(event3);
               })
               // Consistency marker is computed in AFTER_COMMIT phase, so we retrieve
               // it and the source set after that:
               .whenComplete(context -> {
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                   afterCommitEvents.set(transaction.source(sourcingCondition));

                   consistencyMarker.set(transaction.appendPosition());
               });
            awaitSuccessfulCompletion(uow.execute());

            // then
            assertNull(beforeCommitEvents.get().first().asCompletableFuture().join());
            StepVerifier.create(FluxUtils.of(afterCommitEvents.get()))
                        .assertNext(entry -> assertPositionAndEvent(entry, 1, event1))
                        .assertNext(entry -> assertPositionAndEvent(entry, 2, event2))
                        .assertNext(entry -> assertPositionAndEvent(entry, 3, event3))
                        .verifyComplete();
            assertEquals(
                    GlobalIndexConsistencyMarker.position(new GlobalIndexConsistencyMarker(3)),
                    GlobalIndexConsistencyMarker.position(consistencyMarker.get())
            );
        }

        @Test
        void sourceReturnsOnlyCommitedEvents() {
            // given
            var event1 = eventMessage(0);
            var event2 = eventMessage(1);
            var sourcingCondition = SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA);
            var beforeCommitEvents = new AtomicReference<MessageStream<? extends EventMessage>>();
            var afterCommitEvents = new AtomicReference<MessageStream<? extends EventMessage>>();

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                   transaction.appendEvent(event1);
                   transaction.appendEvent(event2);
                   beforeCommitEvents.set(transaction.source(sourcingCondition));
               })
               .runOnAfterCommit(context -> {
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                   afterCommitEvents.set(transaction.source(sourcingCondition));
               });
            awaitSuccessfulCompletion(uow.execute());

            // then: before commit - no events should be visible
            StepVerifier.create(FluxUtils.of(beforeCommitEvents.get()))
                        .verifyComplete();

            // then: after commit - both events should be visible
            StepVerifier.create(FluxUtils.of(afterCommitEvents.get()))
                        .assertNext(entry -> assertPositionAndEvent(entry, 1, event1))
                        .assertNext(entry -> assertPositionAndEvent(entry, 2, event2))
                        .verifyComplete();
        }

        @Test
        void appendCommitsOfNonExistentTagWhenOfTwoNonOverlappingTagsOneYieldedNoEvents() {
            Tag nonExistentTag = new Tag("nonExistent", "tag");
            EventCriteria nonExistingCriteria = EventCriteria.havingTags(nonExistentTag);
            Tag existentTag = new Tag("existent", "tag");
            EventCriteria existingCriteria = EventCriteria.havingTags(existentTag);

            appendEventForTag(existentTag);
            testCanCommitTag(nonExistingCriteria, existingCriteria, nonExistentTag);
        }

        @Test
        void appendCommitsOfExistentTagWhenOfTwoNonOverlappingTagsOneYieldedNoEvents() {
            Tag nonExistentTag = new Tag("nonExistent", "tag");
            EventCriteria nonExistingCriteria = EventCriteria.havingTags(nonExistentTag);
            Tag existentTag = new Tag("existent", "tag");
            EventCriteria existingCriteria = EventCriteria.havingTags(existentTag);

            appendEventForTag(existentTag);
            testCanCommitTag(nonExistingCriteria, existingCriteria, existentTag);
        }

        @Test
        void appendEventCreatesAppendConditionFromTagsWhenNoneExists() {
            // given
            Tag eventTag = new Tag("myTag", "myValue");
            var event = new GenericEventMessage(new MessageType(String.class), "tagged payload");

            // when
            var afterCommitEvents = new AtomicReference<MessageStream<? extends EventMessage>>();
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                   // No source() call — the AppendCondition should be created by appendEvent
                   context.putResource(EntityMetamodel.CREATE_WITHOUT_LOAD, true);
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context, m -> Set.of(eventTag));
                   transaction.appendEvent(event);
               })
               .whenComplete(context -> {
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                   afterCommitEvents.set(
                           transaction.source(SourcingCondition.conditionFor(EventCriteria.havingTags(eventTag)))
                   );
               });
            awaitSuccessfulCompletion(uow.execute());

            // then
            StepVerifier.create(FluxUtils.of(afterCommitEvents.get()))
                        .assertNext(entry -> assertEquals("tagged payload", entry.message().payload()))
                        .verifyComplete();
        }

        private ConsistencyMarker appendEventForTag(Tag tag) {
            AppendTransaction<Object> appendTransaction = eventStorageEngine.appendEvents(
                AppendCondition.none(),
                processingContext,
                new GenericTaggedEventMessage<>(
                    new GenericEventMessage(new MessageType(String.class), "my payload"),
                    Set.of(tag)
                )
            )
            .thenApply(this::castTransaction)
            .join();

            return appendTransaction.commit()
                .thenCompose(v -> appendTransaction.afterCommit(v))
                .join();
        }

        @SuppressWarnings("unchecked")
        private AppendTransaction<Object> castTransaction(AppendTransaction<?> at) {
            return (AppendTransaction<Object>) at;
        }

        private void testCanCommitTag(EventCriteria nonExistingCriteria, EventCriteria existingCriteria,
                                      Tag tagToCommitOn) {

            var uow = aUnitOfWork();
            awaitSuccessfulCompletion(uow.executeWithResult(context -> {
                // Transaction which will result in even being appended for non-existent tag
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context,
                                                                                    m -> Set.of(tagToCommitOn));

                // Read both streams, with non-existing empty
                FluxUtils.of(transaction.source(SourcingCondition.conditionFor(nonExistingCriteria))).blockLast();
                FluxUtils.of(transaction.source(SourcingCondition.conditionFor(existingCriteria))).blockLast();

                transaction.appendEvent(new GenericEventMessage(new MessageType(String.class), "my payload"));

                return MessageStream.empty().asCompletableFuture();
            }));
        }
    }

    @Nested
    class OnAppendCallbacks {

        @Test
        void appendEventNotifiesRegisteredCallbacks() {
            // given
            var event1 = eventMessage(0);
            var onAppendCallback1 = new ArrayList<EventMessage>();
            var onAppendCallback2 = new ArrayList<EventMessage>();

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                transaction.onAppend(onAppendCallback1::add);
                transaction.onAppend(onAppendCallback2::add);
                transaction.appendEvent(event1);
            });
            awaitSuccessfulCompletion(uow.execute());

            // then
            assertEquals(1, onAppendCallback1.size());
            assertEquals(1, onAppendCallback2.size());
            assertEquals(event1.identifier(), onAppendCallback1.getFirst().identifier());
            assertEquals(event1.identifier(), onAppendCallback2.getFirst().identifier());
        }

        @Test
        void appendEventNotifierRegisteredCallbacksEvenWhenTransactionRollback() {
            // given
            var event1 = eventMessage(0);
            var callbackInvoked = new AtomicBoolean(false);

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                transaction.onAppend(event -> callbackInvoked.set(true));
                transaction.appendEvent(event1);
            }).runOnPrepareCommit(context -> {
                throw new RuntimeException("Simulated failure during prepare commit");
            });

            // then
            assertThrows(RuntimeException.class, () -> awaitSuccessfulCompletion(uow.execute()));
            assertTrue(callbackInvoked.get());
        }
    }

    @Nested
    class AppendPosition {

        @Test
        void appendPositionReturnsMinusOneWhenNoEventsAppended() {
            // when
            var result = new AtomicReference<ConsistencyMarker>();
            var uow = aUnitOfWork();
            uow.runOnAfterCommit(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                result.set(transaction.appendPosition());
            });
            awaitSuccessfulCompletion(uow.execute());

            // then
            assertEquals(ConsistencyMarker.ORIGIN, result.get());
        }

        @Test
        void appendPositionReturnsConsistencyMarkerOfTheResultWhenEventsAppended() {
            // when
            var result = new AtomicReference<ConsistencyMarker>();
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                transaction.appendEvent(eventMessage(0));
                transaction.appendEvent(eventMessage(1));
                transaction.appendEvent(eventMessage(2));
                transaction.appendEvent(eventMessage(3));
            })
            // Consistency marker is computed in AFTER_COMMIT phase, so retrieve after that:
            .whenComplete(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                result.set(transaction.appendPosition());
            });
            awaitSuccessfulCompletion(uow.execute());

            // then
            assertEquals(
                    GlobalIndexConsistencyMarker.position(new GlobalIndexConsistencyMarker(4)),
                    GlobalIndexConsistencyMarker.position(result.get())
            );
        }
    }

    @Nested
    class TransactionRollback {

        @Test
        void eventsAreNotAppendedWhenTransactionFails() {
            // given
            var event1 = eventMessage(0);
            var event2 = eventMessage(1);
            var sourcingCondition = SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA);

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                   transaction.appendEvent(event1);
                   transaction.appendEvent(event2);
               })
               .runOnPrepareCommit(context -> {
                   throw new IllegalStateException("Simulated failure during prepare commit");
               });

            // then
            assertThrows(CompletionException.class, () -> awaitExceptionalCompletion(uow.execute()));

            var verificationUow = aUnitOfWork();
            var eventsAfterRollback = new AtomicReference<MessageStream<? extends EventMessage>>();
            verificationUow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                eventsAfterRollback.set(transaction.source(sourcingCondition));
            });
            awaitSuccessfulCompletion(verificationUow.execute());

            StepVerifier.create(FluxUtils.of(eventsAfterRollback.get()))
                        .verifyComplete();
        }

        @Test
        void errorPropagationIsHandledByOnErrorPhase() {
            // given
            var event1 = eventMessage(0);
            var capturedError = new AtomicReference<Throwable>();
            var onCommitExecuted = new AtomicBoolean(false);
            var onAfterCommitExecuted = new AtomicBoolean(false);
            var onPostInvocationExecuted = new AtomicBoolean(false);

            // when
            var uow = aUnitOfWork();
            uow.onError((context, phase, error) -> capturedError.set(error)).runOnPreInvocation(context -> {
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                   transaction.appendEvent(event1);
               })
               .runOnPrepareCommit(context -> {
                   throw new RuntimeException("Simulated failure during prepare commit");
               }).runOnCommit(context -> onCommitExecuted.set(true))
               .runOnAfterCommit(context -> onAfterCommitExecuted.set(true))
               .runOnPostInvocation(context -> onPostInvocationExecuted.set(true));

            RuntimeException exception =
                    assertThrows(CompletionException.class, () -> awaitExceptionalCompletion(uow.execute()));

            // then
            assertNotNull(capturedError.get());
            assertEquals("Simulated failure during prepare commit", capturedError.get().getMessage());
            assertEquals(exception.getCause(), capturedError.get());
            assertFalse(onCommitExecuted.get(), "Commit step should not execute after an error");
            assertFalse(onAfterCommitExecuted.get(), "After commit step should not execute after an error");
            assertTrue(onPostInvocationExecuted.get(), "Post invocation step should be executed after an error");
        }
    }

    @Nested
    class OverrideAppendCondition {

        @Test
        void overrideWithoutSourcingReceivesNoCondition() {
            // given
            Tag uniqueTag = new Tag("courseName", "uniqueCourse");
            EventCriteria uniqueCriteria = EventCriteria.havingTags(uniqueTag);
            var receivedCondition = new AtomicReference<AppendCondition>();

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                transaction.overrideAppendCondition(condition -> {
                    receivedCondition.set(condition);
                    return AppendCondition.withCriteria(uniqueCriteria);
                });
                transaction.appendEvent(eventMessage(0));
            });
            awaitSuccessfulCompletion(uow.execute());

            // then
            assertThat(receivedCondition.get()).isEqualTo(AppendCondition.none());
        }

        @Test
        void overrideAfterSourcingReceivesDerivedCondition() {
            // given - pre-populate an event so sourcing produces a non-ORIGIN marker
            var setupUow = aUnitOfWork();
            setupUow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                transaction.appendEvent(eventMessage(0));
            });
            awaitSuccessfulCompletion(setupUow.execute());

            var receivedCondition = new AtomicReference<AppendCondition>();

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                FluxUtils.of(transaction.source(SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA))).blockLast();
                transaction.overrideAppendCondition(condition -> {
                    receivedCondition.set(condition);
                    return condition;
                });
                transaction.appendEvent(eventMessage(1));
            });
            awaitSuccessfulCompletion(uow.execute());

            // then
            assertThat(receivedCondition.get()).isNotNull();
            assertThat(receivedCondition.get().criteria().flatten()).containsAll(TEST_AGGREGATE_CRITERIA.flatten());
            assertThat(receivedCondition.get().consistencyMarker()).isNotEqualTo(ConsistencyMarker.ORIGIN);
        }

        @Test
        void chainingMultipleOverridesAppliesInOrder() {
            // given
            Tag tag1 = new Tag("step", "first");
            Tag tag2 = new Tag("step", "second");
            EventCriteria criteria1 = EventCriteria.havingTags(tag1);
            EventCriteria criteria2 = EventCriteria.havingTags(tag2);
            var receivedBySecondOverride = new AtomicReference<AppendCondition>();

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                // first override: replace criteria with criteria1
                transaction.overrideAppendCondition(condition -> condition.replaceCriteria(criteria1));
                // second override: receives output of first, replace with criteria2
                transaction.overrideAppendCondition(condition -> {
                    receivedBySecondOverride.set(condition);
                    return condition.replaceCriteria(criteria2);
                });
                transaction.appendEvent(eventMessage(0));
            });
            awaitSuccessfulCompletion(uow.execute());

            // then - second override received the output of the first
            assertThat(receivedBySecondOverride.get()).isNotNull();
            assertThat(receivedBySecondOverride.get().criteria().flatten()).containsAll(criteria1.flatten());
        }

        @Test
        void overrideReplaceCriteriaPreservesMarker() {
            // given - pre-populate an event so sourcing produces a non-ORIGIN marker
            var setupUow = aUnitOfWork();
            setupUow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                transaction.appendEvent(eventMessage(0));
            });
            awaitSuccessfulCompletion(setupUow.execute());

            Tag narrowTag = new Tag("narrow", "criteria");
            EventCriteria narrowCriteria = EventCriteria.havingTags(narrowTag);
            var finalCondition = new AtomicReference<AppendCondition>();

            // when - source to get a marker, then narrow criteria
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                FluxUtils.of(transaction.source(SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA))).blockLast();
                transaction.overrideAppendCondition(condition -> {
                    AppendCondition narrowed = condition.replaceCriteria(narrowCriteria);
                    finalCondition.set(narrowed);
                    return narrowed;
                });
                transaction.appendEvent(eventMessage(1));
            });
            awaitSuccessfulCompletion(uow.execute());

            // then - marker preserved from sourcing, criteria replaced
            assertThat(finalCondition.get()).isNotNull();
            assertThat(finalCondition.get().criteria().flatten()).containsAll(narrowCriteria.flatten());
            assertThat(finalCondition.get().consistencyMarker()).isNotEqualTo(ConsistencyMarker.ORIGIN);
        }

        @Test
        void overrideReturningNoneBypassesConflictDetection() {
            // given - pre-populate an event
            var setupUow = aUnitOfWork();
            setupUow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                transaction.appendEvent(eventMessage(0));
            });
            awaitSuccessfulCompletion(setupUow.execute());

            // when - source (gets a marker), then another tx appends a conflicting event,
            //        but the override returns none() to bypass conflict detection
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                FluxUtils.of(transaction.source(SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA))).blockLast();
                transaction.overrideAppendCondition(condition -> AppendCondition.none());
                transaction.appendEvent(eventMessage(1));
            });

            // then - should succeed despite potential conflicts
            awaitSuccessfulCompletion(uow.execute());
        }

        @Test
        void noOverrideDoesNotAffectNormalFlow() {
            // given
            var event = eventMessage(0);
            var appendPosition = new AtomicReference<ConsistencyMarker>();

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                   FluxUtils.of(transaction.source(SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA))).blockLast();
                   transaction.appendEvent(event);
               })
               .whenComplete(context -> {
                   EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                   appendPosition.set(transaction.appendPosition());
               });
            awaitSuccessfulCompletion(uow.execute());

            // then - event was appended normally
            assertThat(appendPosition.get()).isNotEqualTo(ConsistencyMarker.ORIGIN);
        }

        @Test
        void overrideReturningNullIsTreatedAsNone() {
            // given - pre-populate an event
            var setupUow = aUnitOfWork();
            setupUow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                transaction.appendEvent(eventMessage(0));
            });
            awaitSuccessfulCompletion(setupUow.execute());

            // when - source (creates a condition), then override returns null
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                FluxUtils.of(transaction.source(SourcingCondition.conditionFor(TEST_AGGREGATE_CRITERIA))).blockLast();
                transaction.overrideAppendCondition(condition -> null);
                transaction.appendEvent(eventMessage(1));
            });

            // then - should succeed (null treated as AppendCondition.none(), bypassing conflict detection)
            awaitSuccessfulCompletion(uow.execute());
        }

        @Test
        void overrideRejectsNullOperator() {
            // given
            var uow = aUnitOfWork();

            // when / then
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = defaultEventStoreTransactionFor(context);
                assertThatThrownBy(() -> transaction.overrideAppendCondition(null))
                        .isInstanceOf(NullPointerException.class);
            });
            awaitSuccessfulCompletion(uow.execute());
        }
    }

    private EventStoreTransaction defaultEventStoreTransactionFor(ProcessingContext processingContext) {
        return defaultEventStoreTransactionFor(processingContext, m -> Set.of(AGGREGATE_ID_TAG));
    }

    private EventStoreTransaction defaultEventStoreTransactionFor(ProcessingContext processingContext,
                                                                  TagResolver tagResolver) {
        return processingContext.computeResourceIfAbsent(
                testEventStoreTransactionKey,
                () -> new DefaultEventStoreTransaction(
                        eventStorageEngine,
                        processingContext,
                        event -> new GenericTaggedEventMessage<>(event, tagResolver.resolve(event))
                )
        );
    }

    protected static EventMessage eventMessage(int seq) {
        return new GenericEventMessage(new MessageType("test", "event", "0.0.1"), "event-" + seq);
    }

    private static void assertPositionAndEvent(MessageStream.Entry<? extends EventMessage> actual,
                                               long expectedPosition,
                                               EventMessage expectedEvent) {
        Optional<TrackingToken> actualToken = TrackingToken.fromContext(actual);
        assertTrue(actualToken.isPresent());
        OptionalLong actualPosition = actualToken.get().position();
        assertTrue(actualPosition.isPresent());
        assertEquals(expectedPosition, actualPosition.getAsLong());
        assertEvent(actual.message(), expectedEvent);
    }

    private static void assertEvent(EventMessage actual, EventMessage expected) {
        assertEquals(expected.identifier(), actual.identifier());
        assertEquals(expected.payload(), actual.payload());
        assertEquals(expected.timestamp(), actual.timestamp());
        assertEquals(expected.metadata(), actual.metadata());
    }
}