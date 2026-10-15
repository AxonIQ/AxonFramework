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

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.ConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.SimpleCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandHandlingComponent;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.entity.ConcreteEntityMetamodel;
import org.axonframework.modelling.entity.EntityCommandHandlerInterceptor;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.entity.child.ChildEntityFieldDefinition;
import org.axonframework.modelling.entity.child.SingleEntityChildMetamodel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.util.AssertUtils.awaitExceptionalCompletion;
import static org.axonframework.common.util.AssertUtils.awaitSuccessfulCompletion;
import static org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils.aUnitOfWork;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Test class validating the {@link AppendCriteriaInterceptor}.
 */
class AppendCriteriaInterceptorTest {

    private static final QualifiedName USE_CREDITS = new QualifiedName("credits.UseCredits");
    private static final QualifiedName TOP_UP_CREDITS = new QualifiedName("credits.TopUpCredits");
    private static final QualifiedName REPLACE_CARD = new QualifiedName("credits.ReplaceCard");
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
            AppendCriteriaResolver resolver = (command, context, sourcingCriteria) -> {
                resolvedCommands.add(command);
                receivedSourcingCriteria.add(sourcingCriteria);
                return sourcingCriteria;
            };
            CommandHandlingComponent component =
                    new InterceptingCommandHandlingComponent(
                            List.of(new AppendCriteriaInterceptor(
                                    eventStore,
                                    resolver
                            )),
                            delegate
                    );
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
                eventStore.transaction(context).appendEvent(asEventMessage(new CreditsChanged("one")));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor(
                            eventStore,
                            (command, context, sourcingCriteria) -> {
                                received.add(sourcingCriteria);
                                return sourcingCriteria;
                            }
                    )),
                    delegate
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
            seed.runOnPreInvocation(context -> eventStore.transaction(context)
                                                         .appendEvent(asEventMessage(new CreditsChanged("one"))));
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
                eventStore.transaction(context).appendEvent(asEventMessage(new CreditsChanged("one")));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor(
                            eventStore,
                            (command, context, criteria) -> commandCriteria
                    )),
                    delegate
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
                eventStore.transaction(context).appendEvent(asEventMessage(new CreditsChanged("one")));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor(
                            eventStore,
                            (command, context, sourcingCriteria) -> {
                                receivedSourcingCriteria.set(sourcingCriteria);
                                return commandCriteria;
                            }
                    )),
                    delegate
            );

            // when
            handleSuccessfully(component, command(USE_CREDITS, "one"));

            // then
            assertThat(receivedSourcingCriteria.get()).isEqualTo(AppendCondition.none().criteria());
            assertThat(finalCondition.get().criteria()).isEqualTo(commandCriteria);
            assertThat(finalCondition.get().consistencyMarker()).isEqualTo(ConsistencyMarker.ORIGIN);
        }

        @Test
        void resolverAddingNoRestrictionLeavesANonSourcingCommandUnconditional() {
            // given a command that sources nothing, whose resolver returns the criteria it was supplied
            AtomicReference<AppendCondition> finalCondition = new AtomicReference<>();
            SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("unconditional");
            delegate.subscribe(USE_CREDITS, (command, context) -> {
                eventStore.transaction(context).overrideAppendCondition(condition -> {
                    finalCondition.set(condition);
                    return condition;
                });
                eventStore.transaction(context).appendEvent(asEventMessage(new CreditsChanged("one")));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor(
                            eventStore,
                            (command, context, sourcingCriteria) -> sourcingCriteria
                    )),
                    delegate
            );

            // when
            handleSuccessfully(component, command(USE_CREDITS, "one"));

            // then the append stays unconditional rather than dropping to a check from ORIGIN
            assertThat(finalCondition.get()).isSameAs(AppendCondition.none());
            assertThat(finalCondition.get().consistencyMarker()).isEqualTo(ConsistencyMarker.INFINITY);
        }

        @Test
        void resolverFailurePreventsTheQueuedEventFromCommitting() {
            // given
            SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("failing-resolver");
            delegate.subscribe(USE_CREDITS, (command, context) -> {
                eventStore.transaction(context).appendEvent(asEventMessage(new CreditsChanged("one")));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor(
                            eventStore,
                            (command, context, sourcingCriteria) -> {
                                throw new IllegalStateException("Cannot resolve append criteria");
                            }
                    )),
                    delegate
            );
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> component.handle(command(USE_CREDITS, "one"), context));

            // when
            assertThatThrownBy(() -> awaitExceptionalCompletion(uow.execute()))
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("Cannot resolve append criteria");

            // then
            assertThat(storageEngine.latestToken().orTimeout(5, TimeUnit.SECONDS).join().position()).hasValue(-1);
        }

        @Test
        void nullResolverResultPreventsTheQueuedEventFromCommitting() {
            // given
            SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("null-resolver");
            delegate.subscribe(USE_CREDITS, (command, context) -> {
                eventStore.transaction(context).appendEvent(asEventMessage(new CreditsChanged("one")));
                return MessageStream.empty();
            });
            CommandHandlingComponent component = new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor(
                            eventStore,
                            (command, context, sourcingCriteria) -> null
                    )),
                    delegate
            );
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> component.handle(command(USE_CREDITS, "one"), context));

            // when
            assertThatThrownBy(() -> awaitExceptionalCompletion(uow.execute()))
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(NullPointerException.class)
                    .hasStackTraceContaining("append criteria resolver returned null");

            // then
            assertThat(storageEngine.latestToken().orTimeout(5, TimeUnit.SECONDS).join().position()).hasValue(-1);
        }
    }

    @Nested
    class Validation {

        @Test
        void duplicateDefinitionsFailBeforeTheDelegateHandlesTheCommand() {
            // given
            SimpleCommandHandlingComponent delegate = SimpleCommandHandlingComponent.create("duplicate");
            delegate.subscribe(USE_CREDITS, (command, context) -> MessageStream.empty());
            CommandHandlingComponent first = new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor(
                            eventStore,
                            (command, context, sourcingCriteria) -> sourcingCriteria
                    )),
                    delegate
            );
            CommandHandlingComponent second = new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor(
                            eventStore,
                            (command, context, sourcingCriteria) -> sourcingCriteria
                    )),
                    first
            );
            CommandMessage command = command(USE_CREDITS, "one");

            // when / then
            var result = second.handle(command, new StubProcessingContext());
            assertThatThrownBy(() -> result.asCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("Cannot apply append criteria for command [credits.UseCredits]")
                    .hasStackTraceContaining("Append criteria have already been defined");
        }
    }

    /**
     * Covers the two ways users register the interceptor: on a {@link CommandHandlingModule}, and on an entity through
     * {@link EntityCommandHandlerInterceptor#of(org.axonframework.messaging.core.MessageHandlerInterceptor)}.
     */
    @Nested
    class Registration {

        @Test
        void moduleInterceptorAppliesTheResolverThroughTheConfiguredEventStore() {
            // given a module whose interceptor takes the EventStore from the configuration
            List<CommandMessage> resolvedCommands = new CopyOnWriteArrayList<>();
            AtomicReference<AppendCondition> finalCondition = new AtomicReference<>();
            EventCriteria commandCriteria = EventCriteria.havingTags("decision", "use-credits");
            EventSourcingConfigurer configurer = EventSourcingConfigurer
                    .create()
                    .componentRegistry(registry -> registry.registerComponent(EventStore.class, c -> eventStore))
                    .registerCommandHandlingModule(
                            CommandHandlingModule.named("credits")
                                                 .commandHandlers()
                                                 .commandHandlingComponent(c -> observingComponent(finalCondition))
                                                 .intercepted(c -> new AppendCriteriaInterceptor(
                                                         c.getComponent(EventStore.class),
                                                         (command, context, sourcingCriteria) -> {
                                                             resolvedCommands.add(command);
                                                             return commandCriteria;
                                                         }
                                                 ))
                    );
            AxonConfiguration configuration = configurer.start();
            CommandMessage use = command(USE_CREDITS, "one");

            try {
                // when
                configuration.getComponent(CommandBus.class).dispatch(use, null)
                             .orTimeout(5, TimeUnit.SECONDS)
                             .orTimeout(5, TimeUnit.SECONDS).join();

                // then the resolver ran, and its criteria reached the transaction of the configured event store
                assertThat(resolvedCommands).containsExactly(use);
                assertThat(finalCondition.get().criteria()).isEqualTo(commandCriteria);
            } finally {
                configuration.shutdown();
            }
        }

        @Test
        void entityInterceptorAppliesToInstanceCommandsOfTheEntity() {
            // given
            AtomicReference<AppendCondition> finalCondition = new AtomicReference<>();
            EventCriteria commandCriteria = EventCriteria.havingTags("decision", "use-credits");
            EntityMetamodel<Balance> metamodel = ConcreteEntityMetamodel
                    .forEntityClass(Balance.class)
                    .instanceCommandHandler(USE_CREDITS, (command, entity, context) ->
                            sourceAccountAndAppend(command, context, finalCondition))
                    .commandHandlerInterceptor(EntityCommandHandlerInterceptor.of(new AppendCriteriaInterceptor(
                            eventStore, (command, context, sourcingCriteria) -> commandCriteria
                    )))
                    .build();

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> metamodel.handleInstance(command(USE_CREDITS, "one"),
                                                                       new Balance(),
                                                                       context));
            awaitSuccessfulCompletion(uow.execute());

            // then
            assertThat(finalCondition.get().criteria()).isEqualTo(commandCriteria);
            assertThat(finalCondition.get().consistencyMarker()).isNotEqualTo(ConsistencyMarker.ORIGIN);
        }

        @Test
        void entityInterceptorAppliesToCreationalCommandsWithoutAnEntity() {
            // given a creational command that sources nothing and reserves a key
            AtomicReference<AppendCondition> finalCondition = new AtomicReference<>();
            EventCriteria reservation = EventCriteria.havingTags("accountId", "new");
            EntityMetamodel<Balance> metamodel = ConcreteEntityMetamodel
                    .forEntityClass(Balance.class)
                    .creationalCommandHandler(TOP_UP_CREDITS, (command, context) -> {
                        observeFinalCondition(context, finalCondition);
                        eventStore.transaction(context).appendEvent(asEventMessage(new CreditsChanged("new")));
                        return MessageStream.empty().cast();
                    })
                    .commandHandlerInterceptor(EntityCommandHandlerInterceptor.of(new AppendCriteriaInterceptor(
                            eventStore, (command, context, sourcingCriteria) -> reservation
                    )))
                    .build();

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> metamodel.handleCreate(command(TOP_UP_CREDITS, "new"), context));
            awaitSuccessfulCompletion(uow.execute());

            // then the criteria are checked against the whole history
            assertThat(finalCondition.get().criteria()).isEqualTo(reservation);
            assertThat(finalCondition.get().consistencyMarker()).isEqualTo(ConsistencyMarker.ORIGIN);
        }

        @Test
        void entityInterceptorOnTheParentAppliesToCommandsForwardedToAChildEntity() {
            // given
            AtomicReference<AppendCondition> finalCondition = new AtomicReference<>();
            List<CommandMessage> resolvedCommands = new ArrayList<>();
            EventCriteria commandCriteria = EventCriteria.havingTags("decision", "replace-card");
            EntityMetamodel<Card> cardMetamodel = ConcreteEntityMetamodel
                    .forEntityClass(Card.class)
                    .instanceCommandHandler(REPLACE_CARD, (command, card, context) ->
                            sourceAccountAndAppend(command, context, finalCondition))
                    .build();
            EntityMetamodel<Balance> metamodel = ConcreteEntityMetamodel
                    .forEntityClass(Balance.class)
                    .addChild(SingleEntityChildMetamodel
                                      .forEntityModel(Balance.class, cardMetamodel)
                                      .childEntityFieldDefinition(ChildEntityFieldDefinition.forGetterEvolver(
                                              Balance::card, (balance, card) -> balance
                                      ))
                                      .build())
                    .commandHandlerInterceptor(EntityCommandHandlerInterceptor.of(new AppendCriteriaInterceptor(
                            eventStore,
                            (command, context, sourcingCriteria) -> {
                                resolvedCommands.add(command);
                                return commandCriteria;
                            }
                    )))
                    .build();
            CommandMessage replaceCard = command(REPLACE_CARD, "one");

            // when
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> metamodel.handleInstance(replaceCard, new Balance(), context));
            awaitSuccessfulCompletion(uow.execute());

            // then
            assertThat(resolvedCommands).containsExactly(replaceCard);
            assertThat(finalCondition.get().criteria()).isEqualTo(commandCriteria);
        }

        @Test
        void rejectsNullEventStoreAndResolver() {
            // given
            AppendCriteriaResolver resolver = (command, context, sourcingCriteria) -> sourcingCriteria;

            // when / then
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new AppendCriteriaInterceptor(null, resolver))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("EventStore cannot be null");
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new AppendCriteriaInterceptor(eventStore, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("append criteria resolver cannot be null");
        }

        private CommandHandlingComponent observingComponent(AtomicReference<AppendCondition> finalCondition) {
            SimpleCommandHandlingComponent component = SimpleCommandHandlingComponent.create("configured");
            component.subscribe(USE_CREDITS, (command, context) -> {
                observeFinalCondition(context, finalCondition);
                eventStore.transaction(context).appendEvent(asEventMessage(new CreditsChanged("one")));
                return MessageStream.empty();
            });
            return component;
        }

        private MessageStream.Single<CommandResultMessage> sourceAccountAndAppend(
                CommandMessage command,
                ProcessingContext context,
                AtomicReference<AppendCondition> finalCondition
        ) {
            observeFinalCondition(context, finalCondition);
            return AppendCriteriaInterceptorTest.this.sourceAccountAndAppend(command, context);
        }

        private void observeFinalCondition(ProcessingContext context,
                                           AtomicReference<AppendCondition> finalCondition) {
            // registered after the interceptor's override, so it observes the condition that override produced
            eventStore.transaction(context).overrideAppendCondition(condition -> {
                finalCondition.set(condition);
                return condition;
            });
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
        eventStore.transaction(context).appendEvent(asEventMessage(new CreditsChanged(accountId)));
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

    private static class Balance {

        private final Card card = new Card();

        Card card() {
            return card;
        }
    }

    private static class Card {

    }
}
