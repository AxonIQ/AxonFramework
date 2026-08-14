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

package org.axonframework.eventsourcing;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.commandhandling.AppendCriteriaResolvingCommandHandlingComponent;
import org.axonframework.eventsourcing.eventstore.AnnotationBasedTagResolver;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.SimpleCommandHandlingComponent;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.util.AssertUtils.awaitExceptionalCompletion;
import static org.axonframework.common.util.AssertUtils.awaitSuccessfulCompletion;
import static org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils.aUnitOfWork;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * End-to-end concurrency tests for command-specific append criteria.
 */
class CommandAppendCriteriaConcurrencyTest {

    private static final QualifiedName DECIDE = new QualifiedName("credits.Decide");

    @Nested
    class TypeRestriction {

        @Test
        void concurrentIncludedEventRejectsAppend() {
            // given / when
            CompletableFuture<Void> result = executeCreditDecision(new CreditsUsed("account-one"), true);

            // then
            assertRejected(result);
        }

        @Test
        void intentionallyExcludedConcurrentEventDoesNotRejectAppend() {
            // given / when
            CompletableFuture<Void> result = executeCreditDecision(new CreditsToppedUp("account-one"), true);

            // then
            awaitSuccessfulCompletion(result);
        }

        @Test
        void returningSourcingCriteriaUnchangedRetainsSymmetricBehavior() {
            // given / when
            CompletableFuture<Void> result = executeCreditDecision(new CreditsToppedUp("account-one"), false);

            // then
            assertRejected(result);
        }
    }

    @Nested
    class MultipleSources {

        @Test
        void everyRetainedTermOfAMultiEntityUnionDetectsConflicts() {
            // given / when / then
            assertRejected(executeMultiAccountDecision("account-one"));
            assertRejected(executeMultiAccountDecision("account-two"));
        }
    }

    @Nested
    class WithoutSourcing {

        @Test
        void metadataDerivedCriteriaCheckTheCorrectPartitionFromOrigin() {
            // given
            EventStore eventStore = eventStore();
            CommandMessage command = command(Map.of("tenantId", "tenant-one"));
            CommandHandlingComponent component = appendOnlyComponent(eventStore);

            // when
            CompletableFuture<Void> result = aUnitOfWork().executeWithResult(context -> {
                eventStore.publish(null, List.of(asEventMessage(new TenantDecision("tenant-one")))).join();
                component.handle(command, context);
                return CompletableFuture.completedFuture(null);
            });

            // then
            assertRejected(result);
        }

        @Test
        void appendSucceedsWhenNoMatchingEventExists() {
            // given
            EventStore eventStore = eventStore();
            CommandHandlingComponent component = appendOnlyComponent(eventStore);

            // when
            CompletableFuture<Void> result = aUnitOfWork().executeWithResult(context -> {
                component.handle(command(Map.of("tenantId", "tenant-one")), context);
                return CompletableFuture.completedFuture(null);
            });

            // then
            awaitSuccessfulCompletion(result);
        }
    }

    private static CompletableFuture<Void> executeCreditDecision(Object concurrentEvent, boolean restrictToUsed) {
        EventStore eventStore = eventStore();
        eventStore.publish(null, List.of(asEventMessage(new CreditsUsed("account-one")))).join();
        EventCriteria sourcingCriteria = EventCriteria
                .havingTags("account", "account-one")
                .andBeingOneOfTypes(
                        new QualifiedName(CreditsToppedUp.class),
                        new QualifiedName(CreditsUsed.class)
                );
        SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("credit-decision");
        delegate.subscribe(DECIDE, (command, context) -> {
            FluxUtils.of(eventStore.transaction(context).source(SourcingCondition.conditionFor(sourcingCriteria)))
                     .blockLast();
            eventStore.publish(null, List.of(asEventMessage(concurrentEvent))).join();
            eventStore.transaction(context).appendEvent(asEventMessage(new CreditDecision("account-one")));
            return MessageStream.empty();
        });
        CommandHandlingComponent component = new AppendCriteriaResolvingCommandHandlingComponent(
                delegate,
                eventStore,
                (command, context, accumulatedCriteria) -> restrictToUsed
                        ? accumulatedCriteria.replaceEventTypes(Set.of(new QualifiedName(CreditsUsed.class)))
                        : accumulatedCriteria
        );
        return execute(component, command(Map.of()));
    }

    private static CompletableFuture<Void> executeMultiAccountDecision(String concurrentAccount) {
        EventStore eventStore = eventStore();
        EventCriteria first = EventCriteria.havingTags("account", "account-one");
        EventCriteria second = EventCriteria.havingTags("account", "account-two");
        SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("multi-account-decision");
        delegate.subscribe(DECIDE, (command, context) -> {
            EventStoreTransaction transaction = eventStore.transaction(context);
            FluxUtils.of(transaction.source(SourcingCondition.conditionFor(first))).blockLast();
            FluxUtils.of(transaction.source(SourcingCondition.conditionFor(second))).blockLast();
            eventStore.publish(null, List.of(asEventMessage(new CreditsUsed(concurrentAccount)))).join();
            transaction.appendEvent(asEventMessage(new CreditDecision("account-one")));
            return MessageStream.empty();
        });
        CommandHandlingComponent component = new AppendCriteriaResolvingCommandHandlingComponent(
                delegate,
                eventStore,
                (command, context, accumulatedCriteria) ->
                        accumulatedCriteria.replaceEventTypes(Set.of(new QualifiedName(CreditsUsed.class)))
        );
        return execute(component, command(Map.of()));
    }

    private static CommandHandlingComponent appendOnlyComponent(EventStore eventStore) {
        SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("tenant-decision");
        delegate.subscribe(DECIDE, (command, context) -> {
            String tenantId = command.metadata().get("tenantId");
            eventStore.transaction(context).appendEvent(asEventMessage(new DecisionRecorded(tenantId)));
            return MessageStream.empty();
        });
        return new AppendCriteriaResolvingCommandHandlingComponent(
                delegate,
                eventStore,
                (command, context, sourcingCriteria) ->
                        EventCriteria.havingTags("tenant", command.metadata().get("tenantId"))
        );
    }

    private static CompletableFuture<Void> execute(CommandHandlingComponent component, CommandMessage command) {
        return aUnitOfWork().executeWithResult(context -> {
            component.handle(command, context);
            return CompletableFuture.completedFuture(null);
        });
    }

    private static EventStore eventStore() {
        return new StorageEngineBackedEventStore(
                new InMemoryEventStorageEngine(),
                new SimpleEventBus(),
                new AnnotationBasedTagResolver()
        );
    }

    private static CommandMessage command(Map<String, String> metadata) {
        return new GenericCommandMessage(new MessageType(DECIDE), "decide", metadata);
    }

    private static void assertRejected(CompletableFuture<Void> result) {
        assertThatThrownBy(() -> awaitExceptionalCompletion(result))
                .hasRootCauseInstanceOf(AppendEventsTransactionRejectedException.class);
    }

    private record CreditsToppedUp(@EventTag(key = "account") String account) {
    }

    private record CreditsUsed(@EventTag(key = "account") String account) {
    }

    private record CreditDecision(@EventTag(key = "account") String account) {
    }

    private record TenantDecision(@EventTag(key = "tenant") String tenant) {
    }

    private record DecisionRecorded(@EventTag(key = "tenant") String tenant) {
    }
}
