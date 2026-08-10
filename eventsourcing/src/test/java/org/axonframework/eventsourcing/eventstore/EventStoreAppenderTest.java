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

import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.util.AssertUtils.awaitSuccessfulCompletion;
import static org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils.aUnitOfWork;

/**
 * Test class validating the {@link EventStoreAppender}, obtained through
 * {@link DefaultEventStoreAppender}.
 *
 * @author Mateusz Nowak
 */
class EventStoreAppenderTest {

    private static final MessageTypeResolver MESSAGE_TYPE_RESOLVER = new ClassBasedMessageTypeResolver();

    private InMemoryEventStorageEngine eventStorageEngine;
    private EventStore eventStore;

    @BeforeEach
    void setUp() {
        eventStorageEngine = new InMemoryEventStorageEngine();
        EventBus eventBus = new SimpleEventBus();
        TagResolver tagResolver = event -> event.payload() instanceof TaggedPayload taggedPayload
                ? taggedPayload.tags()
                : Set.of();
        eventStore = new StorageEngineBackedEventStore(eventStorageEngine, eventBus, tagResolver);
    }

    private record TaggedPayload(Set<Tag> tags) {

    }

    @Nested
    class ForContext {

        @Test
        void returnsFreshInstanceEveryInvocation() {
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreAppender first = EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER);
                EventStoreAppender second = EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER);
                assertThat(first).isNotSameAs(second);
            });
            awaitSuccessfulCompletion(uow.execute());
        }
    }

    @Nested
    class ConditionalWithEventCriteria {

        @Test
        void withoutPriorConditionChecksFromOrigin() {
            var criteria = EventCriteria.havingTags(Tag.of("scope", "target"));
            var resolvedCondition = new AtomicReference<AppendCondition>();

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreAppender appender = EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER);
                appender.conditional(criteria);
                captureResolvedCondition(context, resolvedCondition);
                appender.append(new TaggedPayload(Set.of(Tag.of("scope", "target"))));
            });
            awaitSuccessfulCompletion(uow.execute());

            assertThat(resolvedCondition.get().consistencyMarker()).isEqualTo(ConsistencyMarker.ORIGIN);
            assertThat(resolvedCondition.get().criteria()).isEqualTo(criteria);
        }

        @Test
        void afterSourcingRetainsTheSourcedMarker() {
            appendDirectly(new TaggedPayload(Set.of(Tag.of("scope", "sourced"))));

            var sourcingCriteria = EventCriteria.havingTags(Tag.of("scope", "sourced"));
            var replacementCriteria = EventCriteria.havingTags(Tag.of("scope", "replacement"));
            var resolvedCondition = new AtomicReference<AppendCondition>();

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = eventStore.transaction(context);
                FluxUtils.of(transaction.source(SourcingCondition.conditionFor(sourcingCriteria))).blockLast();

                EventStoreAppender appender = EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER);
                appender.conditional(replacementCriteria);
                captureResolvedCondition(context, resolvedCondition);
                appender.append(new TaggedPayload(Set.of(Tag.of("scope", "replacement"))));
            });
            awaitSuccessfulCompletion(uow.execute());

            assertThat(resolvedCondition.get().consistencyMarker()).isNotEqualTo(ConsistencyMarker.ORIGIN);
            assertThat(resolvedCondition.get().criteria()).isEqualTo(replacementCriteria);
        }

        @Test
        void failsExplicitlyWhenTheSourcedMarkerIsAggregateBased() {
            var sourcingCriteria = EventCriteria.havingTags(Tag.of("scope", "sourced"));
            var replacementCriteria = EventCriteria.havingTags(Tag.of("scope", "replacement"));

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = eventStore.transaction(context);
                // Simulates an aggregate-based storage engine having resolved this marker from sourcing.
                transaction.overrideAppendCondition(c -> c.withMarker(new AggregateBasedConsistencyMarker("agg-1", 0)));
                FluxUtils.of(transaction.source(SourcingCondition.conditionFor(sourcingCriteria))).blockLast();

                EventStoreAppender appender = EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER);
                // The override composes lazily, so registering it never throws synchronously - only resolving the
                // condition at commit (triggered here by appending) does.
                appender.conditional(replacementCriteria)
                        .append(new TaggedPayload(Set.of(Tag.of("scope", "replacement"))));
            });

            assertThatThrownBy(() -> uow.execute().join())
                    .isInstanceOf(CompletionException.class)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("AggregateBasedConsistencyMarker");
        }
    }

    @Nested
    class ConditionalWithAppendCondition {

        @Test
        void replacesTheCompleteConditionRegardlessOfPriorSourcing() {
            appendDirectly(new TaggedPayload(Set.of(Tag.of("scope", "sourced"))));

            var sourcingCriteria = EventCriteria.havingTags(Tag.of("scope", "sourced"));
            var explicitCriteria = EventCriteria.havingTags(Tag.of("scope", "explicit"));
            var explicitCondition = AppendCondition.withCriteria(explicitCriteria);
            var resolvedCondition = new AtomicReference<AppendCondition>();

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = eventStore.transaction(context);
                FluxUtils.of(transaction.source(SourcingCondition.conditionFor(sourcingCriteria))).blockLast();

                EventStoreAppender appender = EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER);
                appender.conditional(explicitCondition);
                captureResolvedCondition(context, resolvedCondition);
                appender.append(new TaggedPayload(Set.of(Tag.of("scope", "explicit"))));
            });
            awaitSuccessfulCompletion(uow.execute());

            assertThat(resolvedCondition.get()).isEqualTo(explicitCondition);
        }

        @Test
        void noneExplicitlySelectsUnconditionalAppendEvenWithAConflict() {
            var conflictCriteria = EventCriteria.havingTags(Tag.of("scope", "conflict"));
            appendDirectly(new TaggedPayload(Set.of(Tag.of("scope", "conflict"))));

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreAppender appender = EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER);
                appender.conditional(conflictCriteria);
                appender.conditional(AppendCondition.none());
                appender.append(new TaggedPayload(Set.of(Tag.of("scope", "conflict"))));
            });

            awaitSuccessfulCompletion(uow.execute());
        }
    }

    @Nested
    class ConditionalWithTransformation {

        @Test
        void supportsCombiningTheCurrentConditionWithAdditionalCriteria() {
            appendDirectly(new TaggedPayload(Set.of(Tag.of("scope", "sourced"))));

            var sourcingCriteria = EventCriteria.havingTags(Tag.of("scope", "sourced"));
            var extraCriteria = EventCriteria.havingTags(Tag.of("scope", "extra"));
            var sourcedMarker = new AtomicReference<ConsistencyMarker>();
            var resolvedCondition = new AtomicReference<AppendCondition>();

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = eventStore.transaction(context);
                FluxUtils.of(transaction.source(SourcingCondition.conditionFor(sourcingCriteria))).blockLast();
                transaction.overrideAppendCondition(c -> {
                    sourcedMarker.set(c.consistencyMarker());
                    return c;
                });

                EventStoreAppender appender = EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER);
                appender.conditional(current -> current.orCriteria(extraCriteria));
                captureResolvedCondition(context, resolvedCondition);
                appender.append(new TaggedPayload(Set.of(Tag.of("scope", "sourced"))));
            });
            awaitSuccessfulCompletion(uow.execute());

            assertThat(resolvedCondition.get().consistencyMarker()).isEqualTo(sourcedMarker.get());
            assertThat(resolvedCondition.get().criteria().flatten())
                    .containsExactlyInAnyOrderElementsOf(sourcingCriteria.or(extraCriteria).flatten());
        }

        @Test
        void multipleConditionalCallsComposeInRegistrationOrder() {
            var firstCriteria = EventCriteria.havingTags(Tag.of("scope", "first"));
            var secondCriteria = EventCriteria.havingTags(Tag.of("scope", "second"));
            var receivedByOperator = new AtomicReference<AppendCondition>();
            var resolvedCondition = new AtomicReference<AppendCondition>();

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreAppender appender = EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER);
                appender.conditional(firstCriteria);
                appender.conditional(current -> {
                    receivedByOperator.set(current);
                    return current.replaceCriteria(secondCriteria);
                });
                captureResolvedCondition(context, resolvedCondition);
                appender.append(new TaggedPayload(Set.of(Tag.of("scope", "second"))));
            });
            awaitSuccessfulCompletion(uow.execute());

            assertThat(receivedByOperator.get().criteria()).isEqualTo(firstCriteria);
            assertThat(resolvedCondition.get().criteria()).isEqualTo(secondCriteria);
        }
    }

    @Nested
    class NoAssertionWithoutAppending {

        @Test
        void registeringAConditionWithoutAppendingPerformsNoAssertion() {
            var criteria = EventCriteria.havingTags(Tag.of("scope", "unused"));

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context ->
                    EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER).conditional(criteria)
            );

            awaitSuccessfulCompletion(uow.execute());
        }
    }

    @Nested
    class GovernsEveryAppendInTheSameContext {

        @Test
        void rejectsAnAppendThroughAPlainEventAppenderWhenTheRegisteredConditionConflicts() {
            var criteria = EventCriteria.havingTags(Tag.of("scope", "conflict"));
            appendDirectly(new TaggedPayload(Set.of(Tag.of("scope", "conflict"))));

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER).conditional(criteria);
                EventAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER)
                             .append(new TaggedPayload(Set.of(Tag.of("scope", "conflict"))));
            });

            assertThatThrownBy(() -> uow.execute().join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseInstanceOf(AppendEventsTransactionRejectedException.class);
        }
    }

    @Nested
    class BranchIsolation {

        private final Context.ResourceKey<String> UNRELATED_KEY = Context.ResourceKey.withLabel("unrelated");

        @Test
        void resolvingTheAppenderForABranchStillTargetsTheSameSharedTransactionAsTheRoot() {
            var rootCriteria = EventCriteria.havingTags(Tag.of("scope", "root"));
            var branchCriteria = EventCriteria.havingTags(Tag.of("scope", "branch"));
            var receivedByBranch = new AtomicReference<AppendCondition>();
            var resolvedCondition = new AtomicReference<AppendCondition>();

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                // A branch overriding an unrelated resource key - e.g. a per-message branch of a shared batch.
                ProcessingContext branch = context.withResource(UNRELATED_KEY, "sibling-value");

                EventStoreAppender rootAppender =
                        EventStoreAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER);
                EventStoreAppender branchAppender =
                        EventStoreAppender.forContext(branch, eventStore, MESSAGE_TYPE_RESOLVER);
                assertThat(rootAppender).isNotSameAs(branchAppender);

                rootAppender.conditional(rootCriteria);
                branchAppender.conditional(current -> {
                    receivedByBranch.set(current);
                    return current.replaceCriteria(branchCriteria);
                });
                captureResolvedCondition(context, resolvedCondition);
                branchAppender.append(new TaggedPayload(Set.of(Tag.of("scope", "branch"))));
            });
            awaitSuccessfulCompletion(uow.execute());

            // The branch-resolved appender observed the root's registration: both target the same shared
            // transaction, rather than each branch getting its own isolated (and therefore incorrect) condition.
            assertThat(receivedByBranch.get().criteria()).isEqualTo(rootCriteria);
            assertThat(resolvedCondition.get().criteria()).isEqualTo(branchCriteria);
        }
    }

    @Nested
    class UnsupportedTransaction {

        @Test
        void conditionalFailsBeforeAnyEventIsPublishedWhenTheTransactionDoesNotSupportOverriding() {
            var criteria = EventCriteria.havingTags(Tag.of("scope", "unsupported"));
            InMemoryEventStorageEngine isolatedEngine = new InMemoryEventStorageEngine();
            EventStore unsupportedEventStore = new UnsupportedOverrideEventStore(
                    isolatedEngine, new SimpleEventBus(), event -> Set.of()
            );

            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> {
                EventStoreAppender appender =
                        EventStoreAppender.forContext(context, unsupportedEventStore, MESSAGE_TYPE_RESOLVER);
                assertThatThrownBy(() -> appender.conditional(criteria)
                                                  .append(new TaggedPayload(Set.of(Tag.of("scope", "unsupported")))))
                        .isInstanceOf(UnsupportedOperationException.class);
            });
            awaitSuccessfulCompletion(uow.execute());

            var events = new AtomicReference<Boolean>();
            var verificationUow = aUnitOfWork();
            verificationUow.runOnPreInvocation(context -> {
                EventStoreTransaction transaction = unsupportedEventStore.transaction(context);
                MessageStream<? extends EventMessage> stream =
                        transaction.source(SourcingCondition.conditionFor(criteria));
                events.set(FluxUtils.of(stream).collectList().block().isEmpty());
            });
            awaitSuccessfulCompletion(verificationUow.execute());

            assertThat(events.get()).as("no event was published before the UnsupportedOperationException").isTrue();
        }
    }

    /**
     * An {@link EventStore} whose {@link EventStoreTransaction} does not override
     * {@link EventStoreTransaction#overrideAppendCondition(java.util.function.UnaryOperator)}, mirroring a
     * third-party {@code EventStoreTransaction} that has not implemented support for it.
     */
    private static class UnsupportedOverrideEventStore extends StorageEngineBackedEventStore {

        UnsupportedOverrideEventStore(EventStorageEngine eventStorageEngine, EventBus eventBus,
                                      TagResolver tagResolver) {
            super(eventStorageEngine, eventBus, tagResolver);
        }

        @Override
        public EventStoreTransaction transaction(ProcessingContext processingContext) {
            EventStoreTransaction delegate = super.transaction(processingContext);
            return new EventStoreTransaction() {
                @Override
                public MessageStream<? extends EventMessage> source(
                        SourcingCondition condition,
                        @Nullable Consumer<Position> resumePositionCallback
                ) {
                    return delegate.source(condition, resumePositionCallback);
                }

                @Override
                public void appendEvent(EventMessage eventMessage) {
                    delegate.appendEvent(eventMessage);
                }

                @Override
                public void onAppend(Consumer<EventMessage> callback) {
                    delegate.onAppend(callback);
                }

                @Override
                public ConsistencyMarker appendPosition() {
                    return delegate.appendPosition();
                }

                // overrideAppendCondition intentionally not overridden: falls back to the interface default,
                // which throws UnsupportedOperationException.
            };
        }
    }

    private void captureResolvedCondition(ProcessingContext context, AtomicReference<AppendCondition> target) {
        eventStore.transaction(context).overrideAppendCondition(c -> {
            target.set(c);
            return c;
        });
    }

    private void appendDirectly(Object payload) {
        UnitOfWork uow = aUnitOfWork();
        uow.runOnPreInvocation(context ->
                EventAppender.forContext(context, eventStore, MESSAGE_TYPE_RESOLVER).append(payload)
        );
        awaitSuccessfulCompletion(uow.execute());
    }
}
