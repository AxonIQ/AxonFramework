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

package org.axonframework.eventsourcing.commandhandling;

import org.axonframework.eventsourcing.CommandAppendCriteriaResolver;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.SimpleCommandHandlingComponent;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.util.AssertUtils.awaitExceptionalCompletion;
import static org.axonframework.common.util.AssertUtils.awaitSuccessfulCompletion;
import static org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils.aUnitOfWork;

/**
 * Test class validating declarative command append criteria on external command-handling components.
 */
class AppendCriteriaResolvingCommandHandlingComponentTest {

    private static final QualifiedName USE_CREDITS = new QualifiedName("credits.UseCredits");
    private static final QualifiedName TOP_UP_CREDITS = new QualifiedName("credits.TopUpCredits");
    private static final Tag ACCOUNT_ONE = Tag.of("accountId", "one");
    private static final Tag ACCOUNT_TWO = Tag.of("accountId", "two");

    private final InMemoryEventStorageEngine storageEngine = new InMemoryEventStorageEngine();
    private final EventStore eventStore = new StorageEngineBackedEventStore(
            storageEngine,
            new SimpleEventBus(),
            event -> Set.of(Tag.of("accountId", ((CreditsChanged) event.payload()).accountId()))
    );

    @Nested
    class Resolving {

        @Test
        void oneResolverAppliesSeparatelyToEveryCommandOnTheComponent() {
            // given
            List<CommandMessage> resolvedCommands = new ArrayList<>();
            List<EventCriteria> receivedSourcingCriteria = new ArrayList<>();
            CommandHandlingComponent delegate = componentSourcingOneAccountPerCommand();
            CommandAppendCriteriaResolver resolver = (command, context, sourcingCriteria) -> {
                resolvedCommands.add(command);
                receivedSourcingCriteria.add(sourcingCriteria);
                return sourcingCriteria;
            };
            CommandHandlingComponent component =
                    new AppendCriteriaResolvingCommandHandlingComponent(delegate, eventStore, resolver);
            CommandMessage use = command(USE_CREDITS, "one");
            CommandMessage topUp = command(TOP_UP_CREDITS, "two");

            // when
            handleSuccessfully(component, use);
            handleSuccessfully(component, topUp);

            // then
            assertThat(resolvedCommands).containsExactly(use, topUp);
            assertThat(receivedSourcingCriteria).containsExactly(
                    EventCriteria.havingTags(ACCOUNT_ONE),
                    EventCriteria.havingTags(ACCOUNT_TWO)
            );
        }

        @Test
        void resolverReceivesTheCompleteOrUnionFromSeveralSources() {
            // given
            EventCriteria first = EventCriteria.havingTags(ACCOUNT_ONE);
            EventCriteria second = EventCriteria.havingTags(ACCOUNT_TWO);
            List<EventCriteria> received = new ArrayList<>();
            SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("multi-account");
            delegate.subscribe(USE_CREDITS, (command, context) -> {
                FluxUtils.of(eventStore.transaction(context)
                                       .source(SourcingCondition.conditionFor(first))).blockLast();
                FluxUtils.of(eventStore.transaction(context)
                                       .source(SourcingCondition.conditionFor(second))).blockLast();
                eventStore.transaction(context).appendEvent(new GenericEventMessage(
                        new MessageType(CreditsChanged.class), new CreditsChanged("one")
                ));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new AppendCriteriaResolvingCommandHandlingComponent(
                    delegate,
                    eventStore,
                    (command, context, sourcingCriteria) -> {
                        received.add(sourcingCriteria);
                        return sourcingCriteria;
                    }
            );

            // when
            handleSuccessfully(component, command(USE_CREDITS, "one"));

            // then
            assertThat(received).singleElement().satisfies(criteria ->
                    assertThat(criteria.flatten())
                            .containsExactlyInAnyOrderElementsOf(first.or(second).flatten())
            );
        }

        @Test
        void resolvedCriteriaRetainTheConsistencyMarkerEstablishedBySourcing() {
            // given
            var seed = aUnitOfWork();
            seed.runOnPreInvocation(context -> eventStore.transaction(context).appendEvent(new GenericEventMessage(
                    new MessageType(CreditsChanged.class), new CreditsChanged("one")
            )));
            awaitSuccessfulCompletion(seed.execute());
            EventCriteria sourcingCriteria = EventCriteria.havingTags(ACCOUNT_ONE);
            EventCriteria commandCriteria = EventCriteria.havingTags("decision", "use-credits");
            AtomicReference<AppendCondition> finalCondition = new AtomicReference<>();
            SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("sourced-marker");
            delegate.subscribe(USE_CREDITS, (command, context) -> {
                FluxUtils.of(eventStore.transaction(context).source(
                        SourcingCondition.conditionFor(sourcingCriteria)
                )).blockLast();
                eventStore.transaction(context).overrideAppendCondition(condition -> {
                    finalCondition.set(condition);
                    return condition;
                });
                eventStore.transaction(context).appendEvent(new GenericEventMessage(
                        new MessageType(CreditsChanged.class), new CreditsChanged("one")
                ));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new AppendCriteriaResolvingCommandHandlingComponent(
                    delegate, eventStore, (command, context, criteria) -> commandCriteria
            );

            // when
            handleSuccessfully(component, command(USE_CREDITS, "one"));

            // then
            assertThat(finalCondition.get().criteria()).isEqualTo(commandCriteria);
            assertThat(finalCondition.get().consistencyMarker()).isNotEqualTo(ConsistencyMarker.ORIGIN);
        }

        @Test
        void resolvedCriteriaUseOriginWhenTheCommandDidNotSourceEvents() {
            // given
            EventCriteria commandCriteria = EventCriteria.havingTags("username", "unique");
            AtomicReference<EventCriteria> receivedSourcingCriteria = new AtomicReference<>();
            AtomicReference<AppendCondition> finalCondition = new AtomicReference<>();
            SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("no-source-marker");
            delegate.subscribe(USE_CREDITS, (command, context) -> {
                eventStore.transaction(context).overrideAppendCondition(condition -> {
                    finalCondition.set(condition);
                    return condition;
                });
                eventStore.transaction(context).appendEvent(new GenericEventMessage(
                        new MessageType(CreditsChanged.class), new CreditsChanged("one")
                ));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new AppendCriteriaResolvingCommandHandlingComponent(
                    delegate,
                    eventStore,
                    (command, context, sourcingCriteria) -> {
                        receivedSourcingCriteria.set(sourcingCriteria);
                        return commandCriteria;
                    }
            );

            // when
            handleSuccessfully(component, command(USE_CREDITS, "one"));

            // then
            assertThat(receivedSourcingCriteria.get()).isEqualTo(AppendCondition.none().criteria());
            assertThat(finalCondition.get().criteria()).isEqualTo(commandCriteria);
            assertThat(finalCondition.get().consistencyMarker()).isEqualTo(ConsistencyMarker.ORIGIN);
        }

        @Test
        void resolverFailurePreventsTheQueuedEventFromCommitting() {
            // given
            SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("failing-resolver");
            delegate.subscribe(USE_CREDITS, (command, context) -> {
                eventStore.transaction(context).appendEvent(new GenericEventMessage(
                        new MessageType(CreditsChanged.class), new CreditsChanged("one")
                ));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new AppendCriteriaResolvingCommandHandlingComponent(
                    delegate,
                    eventStore,
                    (command, context, sourcingCriteria) -> {
                        throw new IllegalStateException("Cannot resolve append criteria");
                    }
            );
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> component.handle(command(USE_CREDITS, "one"), context));

            // when
            assertThatThrownBy(() -> awaitExceptionalCompletion(uow.execute()))
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("Cannot resolve append criteria");

            // then
            assertThat(storageEngine.latestToken().join().position()).hasValue(-1);
        }

        @Test
        void nullResolverResultPreventsTheQueuedEventFromCommitting() {
            // given
            SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("null-resolver");
            delegate.subscribe(USE_CREDITS, (command, context) -> {
                eventStore.transaction(context).appendEvent(new GenericEventMessage(
                        new MessageType(CreditsChanged.class), new CreditsChanged("one")
                ));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new AppendCriteriaResolvingCommandHandlingComponent(
                    delegate, eventStore, (command, context, sourcingCriteria) -> null
            );
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> component.handle(command(USE_CREDITS, "one"), context));

            // when
            assertThatThrownBy(() -> awaitExceptionalCompletion(uow.execute()))
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(NullPointerException.class)
                    .hasStackTraceContaining("command append criteria resolver returned null");

            // then
            assertThat(storageEngine.latestToken().join().position()).hasValue(-1);
        }
    }

    @Nested
    class Validation {

        @Test
        void duplicateDefinitionsFailBeforeTheDelegateHandlesTheCommand() {
            // given
            SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("duplicate");
            delegate.subscribe(USE_CREDITS, (command, context) -> MessageStream.empty());
            CommandHandlingComponent first = new AppendCriteriaResolvingCommandHandlingComponent(
                    delegate, eventStore, (command, context, sourcingCriteria) -> sourcingCriteria
            );
            CommandHandlingComponent second = new AppendCriteriaResolvingCommandHandlingComponent(
                    first, eventStore, (command, context, sourcingCriteria) -> sourcingCriteria
            );
            CommandMessage command = command(USE_CREDITS, "one");

            // when / then
            var result = second.handle(command, new StubProcessingContext());
            assertThatThrownBy(() -> result.asCompletableFuture().join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("Cannot apply append criteria for command [credits.UseCredits]")
                    .hasStackTraceContaining("Append criteria have already been defined");
        }
    }

    private CommandHandlingComponent componentSourcingOneAccountPerCommand() {
        SimpleCommandHandlingComponent component = SimpleCommandHandlingComponent.create("credits");
        component.subscribe(USE_CREDITS, this::sourceAccountAndAppend);
        component.subscribe(TOP_UP_CREDITS, this::sourceAccountAndAppend);
        return component;
    }

    private MessageStream.Single<CommandResultMessage> sourceAccountAndAppend(
            CommandMessage command,
            ProcessingContext context
    ) {
        String accountId = ((CreditsCommand) command.payload()).accountId();
        EventCriteria criteria = EventCriteria.havingTags("accountId", accountId);
        FluxUtils.of(eventStore.transaction(context).source(SourcingCondition.conditionFor(criteria))).blockLast();
        eventStore.transaction(context).appendEvent(new GenericEventMessage(
                new MessageType(CreditsChanged.class), new CreditsChanged(accountId)
        ));
        return MessageStream.empty();
    }

    private static void handleSuccessfully(CommandHandlingComponent component, CommandMessage command) {
        var uow = aUnitOfWork();
        uow.runOnPreInvocation(context -> component.handle(command, context));
        awaitSuccessfulCompletion(uow.execute());
    }

    private static CommandMessage command(QualifiedName name, String accountId) {
        return new GenericCommandMessage(new MessageType(name), new CreditsCommand(accountId));
    }

    private record CreditsCommand(String accountId) {
    }

    private record CreditsChanged(String accountId) {
    }
}
