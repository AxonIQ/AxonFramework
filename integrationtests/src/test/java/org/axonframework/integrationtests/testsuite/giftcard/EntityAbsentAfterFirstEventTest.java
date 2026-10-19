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

package org.axonframework.integrationtests.testsuite.giftcard;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.EntityMissingAfterFirstEventException;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating what happens when no entity has been created after an event has been sourced for it. A first
 * event that no {@link EntityCreator} matches, a creator that returns {@code null} for its own event, and an entity
 * that nothing could ever create are reported, as they were before static event sourcing handlers existed. Only an
 * entity with a static event sourcing handler may stay absent until its creating event arrives. A declaratively
 * configured entity whose factory does not create it leaves creation to its evolver.
 *
 * @author Mateusz Nowak
 */
class EntityAbsentAfterFirstEventTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    public record CardIssued(@EventTag String cardId, int amount) {

    }

    public record CardNoted(@EventTag String cardId) {

    }

    public record IssueCard(@TargetEntityId String cardId, int amount) {

    }

    public record NoteCard(@TargetEntityId String cardId) {

    }

    public record DescribeCard(@TargetEntityId String cardId) {

    }

    private static AxonConfiguration start(Object commandHandlers, Class<?> entityType) {
        return EventSourcingConfigurer.create()
                                      .registerCommandHandlingModule(
                                              CommandHandlingModule.named("handlers-" + entityType.getSimpleName())
                                                                   .commandHandlers()
                                                                   .autodetectedCommandHandlingComponent(
                                                                           c -> commandHandlers
                                                                   )
                                                                   .build()
                                      )
                                      .registerEntity(EventSourcedEntityModule.autodetected(String.class, entityType))
                                      .start();
    }

    @Nested
    class FirstEventIsNotTheCreatingEventOfAnEntityCreator {

        private CommandGateway commandGateway;

        @EventSourcedEntity(tagKey = "cardId")
        public static class GiftCard {

            @EntityCreator
            public GiftCard(CardIssued event) {
            }
        }

        public static class Handlers {

            @CommandHandler
            public void handle(NoteCard command, EventAppender appender) {
                appender.append(new CardNoted(command.cardId()));
            }

            @CommandHandler
            public String handle(DescribeCard command, @InjectEntity @Nullable GiftCard card) {
                return card == null ? "absent" : "issued";
            }
        }

        @BeforeEach
        void setUp() {
            commandGateway = start(new Handlers(), GiftCard.class).getComponent(CommandGateway.class);
        }

        @Test
        void loadingFailsBecauseNoEntityCreatorMatchesTheFirstEvent() {
            // given a non-creating event is the first event of the card
            assertThat(commandGateway.send(new NoteCard("cardId"), Void.class)).succeedsWithin(TIMEOUT);

            // when
            var description = commandGateway.send(new DescribeCard("cardId"), String.class);

            // then
            assertThat(description).failsWithin(TIMEOUT)
                                   .withThrowableOfType(ExecutionException.class)
                                   .withCauseInstanceOf(AxonConfigurationException.class)
                                   .havingCause()
                                   .withMessageContaining("No suitable @EntityCreator found");
        }
    }

    @Nested
    class FirstEventIsNotTheCreatingEventOfAStaticEventSourcingHandler {

        private CommandGateway commandGateway;

        @EventSourcedEntity(tagKey = "cardId")
        public record GiftCard(int amount) {

            @EventSourcingHandler
            static GiftCard on(CardIssued event, @Nullable GiftCard state) {
                return new GiftCard(event.amount());
            }

            @EventSourcingHandler
            GiftCard on(CardNoted event) {
                return this;
            }
        }

        public static class Handlers {

            @CommandHandler
            public void handle(NoteCard command, EventAppender appender) {
                appender.append(new CardNoted(command.cardId()));
            }

            @CommandHandler
            public void handle(IssueCard command, @InjectEntity @Nullable GiftCard card, EventAppender appender) {
                if (card != null) {
                    throw new IllegalStateException("GiftCard [" + command.cardId() + "] was already issued");
                }
                appender.append(new CardIssued(command.cardId(), command.amount()));
            }

            @CommandHandler
            public String handle(DescribeCard command, @InjectEntity @Nullable GiftCard card) {
                return card == null ? "absent" : "issued with " + card.amount();
            }
        }

        @BeforeEach
        void setUp() {
            commandGateway = start(new Handlers(), GiftCard.class).getComponent(CommandGateway.class);
        }

        @Test
        void entityIsAbsentWhileOnlyANonCreatingEventExists() {
            // given a non-creating event is the first event of the card
            assertThat(commandGateway.send(new NoteCard("cardId"), Void.class)).succeedsWithin(TIMEOUT);

            // when
            var description = commandGateway.send(new DescribeCard("cardId"), String.class);

            // then
            assertThat(description).succeedsWithin(TIMEOUT).isEqualTo("absent");
        }

        @Test
        void creatingEventAfterANonCreatingFirstEventCreatesTheEntity() {
            // given a non-creating event is the first event of the card
            assertThat(commandGateway.send(new NoteCard("cardId"), Void.class)).succeedsWithin(TIMEOUT);

            // when
            var issuing = commandGateway.send(new IssueCard("cardId", 100), Void.class);

            // then
            assertThat(issuing).succeedsWithin(TIMEOUT);
            assertThat(commandGateway.send(new DescribeCard("cardId"), String.class))
                    .succeedsWithin(TIMEOUT).isEqualTo("issued with 100");
        }

        @Test
        void creatingTheEntityAgainIsRejectedOnceItExists() {
            // given a non-creating first event, followed by the creating event
            assertThat(commandGateway.send(new NoteCard("cardId"), Void.class)).succeedsWithin(TIMEOUT);
            assertThat(commandGateway.send(new IssueCard("cardId", 100), Void.class)).succeedsWithin(TIMEOUT);

            // when
            var secondIssuing = commandGateway.send(new IssueCard("cardId", 100), Void.class);

            // then
            assertThat(secondIssuing).failsWithin(TIMEOUT)
                                     .withThrowableOfType(ExecutionException.class)
                                     .havingCause()
                                     .withMessageContaining("was already issued");
        }
    }

    @Nested
    class StaticEventSourcingHandlerDecliningToCreate {

        private CommandGateway commandGateway;

        @EventSourcedEntity(tagKey = "cardId")
        public record GiftCard(int amount) {

            @EventSourcingHandler
            static @Nullable GiftCard on(CardIssued event, @Nullable GiftCard state) {
                if (state != null) {
                    return state;
                }
                // Decline to create a card without value: the entity stays absent.
                return event.amount() > 0 ? new GiftCard(event.amount()) : null;
            }
        }

        public static class Handlers {

            @CommandHandler
            public void handle(IssueCard command, @InjectEntity @Nullable GiftCard card, EventAppender appender) {
                if (card != null) {
                    throw new IllegalStateException("GiftCard [" + command.cardId() + "] was already issued");
                }
                appender.append(new CardIssued(command.cardId(), command.amount()));
            }

            @CommandHandler
            public String handle(DescribeCard command, @InjectEntity @Nullable GiftCard card) {
                return card == null ? "absent" : "issued with " + card.amount();
            }
        }

        @BeforeEach
        void setUp() {
            commandGateway = start(new Handlers(), GiftCard.class).getComponent(CommandGateway.class);
        }

        @Test
        void entityStaysAbsentWhenTheStaticHandlerDeclinesToCreateIt() {
            // given the static handler declines to create a card issued without value
            assertThat(commandGateway.send(new IssueCard("cardId", 0), Void.class)).succeedsWithin(TIMEOUT);

            // when
            var description = commandGateway.send(new DescribeCard("cardId"), String.class);

            // then
            assertThat(description).succeedsWithin(TIMEOUT).isEqualTo("absent");
        }

        @Test
        void laterEventCreatesTheEntityAfterTheStaticHandlerDeclined() {
            // given the static handler declined to create the card for its first event
            assertThat(commandGateway.send(new IssueCard("cardId", 0), Void.class)).succeedsWithin(TIMEOUT);

            // when
            var issuing = commandGateway.send(new IssueCard("cardId", 100), Void.class);

            // then
            assertThat(issuing).succeedsWithin(TIMEOUT);
            assertThat(commandGateway.send(new DescribeCard("cardId"), String.class))
                    .succeedsWithin(TIMEOUT).isEqualTo("issued with 100");
        }
    }

    @Nested
    class DeclarativeEntityCreatedByItsEvolver {

        @EventSourcedEntity(tagKey = "cardId")
        public record StaticGiftCard(int amount) {

            @EventSourcingHandler
            static StaticGiftCard on(CardIssued event, @Nullable StaticGiftCard state) {
                return state != null ? state : new StaticGiftCard(event.amount());
            }
        }

        public record GiftCard(int amount) {

        }

        public static class StaticGiftCardHandlers {

            @CommandHandler
            public void handle(IssueCard command, @InjectEntity @Nullable StaticGiftCard card, EventAppender appender) {
                appender.append(new CardIssued(command.cardId(), command.amount()));
            }

            @CommandHandler
            public String handle(DescribeCard command, @InjectEntity @Nullable StaticGiftCard card) {
                return card == null ? "absent" : "issued with " + card.amount();
            }
        }

        public static class GiftCardHandlers {

            @CommandHandler
            public void handle(IssueCard command, @InjectEntity @Nullable GiftCard card, EventAppender appender) {
                appender.append(new CardIssued(command.cardId(), command.amount()));
            }

            @CommandHandler
            public String handle(DescribeCard command, @InjectEntity @Nullable GiftCard card) {
                return card == null ? "absent" : "issued with " + card.amount();
            }
        }

        private <E> CommandGateway startDeclarative(Class<E> entityType,
                                                    Function<Configuration, EntityEvolver<E>> evolver,
                                                    Object commandHandlers) {
            var entityModule =
                    EventSourcedEntityModule.declarative(String.class, entityType)
                                            .messagingModel((c, model) -> model.entityEvolver(evolver.apply(c))
                                                                               .build())
                                            // A factory that never creates the entity from an event.
                                            .entityFactory(c -> (id, event, context) -> null)
                                            .criteriaResolver(c -> (id, context) -> EventCriteria.havingTags(
                                                    Tag.of("cardId", id)
                                            ))
                                            .build();
            return EventSourcingConfigurer.create()
                                          .registerEntity(entityModule)
                                          .registerCommandHandlingModule(
                                                  CommandHandlingModule.named("declarative-" + entityType.getSimpleName())
                                                                       .commandHandlers()
                                                                       .autodetectedCommandHandlingComponent(
                                                                               c -> commandHandlers
                                                                       )
                                                                       .build()
                                          )
                                          .start()
                                          .getComponent(CommandGateway.class);
        }

        @Test
        void staticEventSourcingHandlerCreatesTheEntityWhenTheFactoryDoesNot() {
            // given a declarative entity whose evolver is annotation-based, with a static event sourcing handler
            CommandGateway commandGateway = startDeclarative(
                    StaticGiftCard.class,
                    c -> new AnnotationBasedEntityEvolvingComponent<>(
                            StaticGiftCard.class,
                            c.getComponent(EventConverter.class),
                            c.getComponent(MessageTypeResolver.class),
                            c.getComponent(ParameterResolverFactory.class),
                            ClasspathHandlerDefinition.forClass(StaticGiftCard.class)
                    ),
                    new StaticGiftCardHandlers()
            );

            // when
            assertThat(commandGateway.send(new IssueCard("cardId", 100), Void.class)).succeedsWithin(TIMEOUT);

            // then
            assertThat(commandGateway.send(new DescribeCard("cardId"), String.class))
                    .succeedsWithin(TIMEOUT).isEqualTo("issued with 100");
        }

        @Test
        void evolverCreatesTheEntityFromAnAbsentStateWhenTheFactoryDoesNot() {
            // given a declarative entity whose evolver creates the entity from an absent state
            CommandGateway commandGateway = startDeclarative(
                    GiftCard.class,
                    c -> (card, event, context) -> card != null
                            ? card
                            : new GiftCard(((CardIssued) event.payload()).amount()),
                    new GiftCardHandlers()
            );

            // when
            assertThat(commandGateway.send(new IssueCard("cardId", 100), Void.class)).succeedsWithin(TIMEOUT);

            // then
            assertThat(commandGateway.send(new DescribeCard("cardId"), String.class))
                    .succeedsWithin(TIMEOUT).isEqualTo("issued with 100");
        }
    }

    @Nested
    class CreatorReturnsNullForItsEvent {

        private CommandGateway commandGateway;

        @EventSourcedEntity(tagKey = "cardId")
        public static class GiftCard {

            @EntityCreator
            public static @Nullable GiftCard create(CardIssued event) {
                return null;
            }
        }

        public static class Handlers {

            @CommandHandler
            public void handle(IssueCard command, @InjectEntity @Nullable GiftCard card, EventAppender appender) {
                if (card != null) {
                    throw new IllegalStateException("GiftCard [" + command.cardId() + "] was already issued");
                }
                appender.append(new CardIssued(command.cardId(), command.amount()));
            }

            @CommandHandler
            public String handle(DescribeCard command, @InjectEntity @Nullable GiftCard card) {
                return card == null ? "absent" : "issued";
            }
        }

        @BeforeEach
        void setUp() {
            commandGateway = start(new Handlers(), GiftCard.class).getComponent(CommandGateway.class);
        }

        @Test
        void creationFailsWithEntityMissingAfterFirstEventException() {
            // when
            var issuing = commandGateway.send(new IssueCard("cardId", 100), Void.class);

            // then
            assertThat(issuing).failsWithin(TIMEOUT)
                               .withThrowableOfType(ExecutionException.class)
                               .withCauseInstanceOf(EntityMissingAfterFirstEventException.class);
        }

        @Test
        void failedCreationDoesNotAppendTheCreatingEvent() {
            // given a creation that failed
            assertThat(commandGateway.send(new IssueCard("cardId", 100), Void.class)).failsWithin(TIMEOUT);

            // when
            var secondIssuing = commandGateway.send(new IssueCard("cardId", 100), Void.class);

            // then the second attempt fails for the same reason, rather than appending a second creating event
            assertThat(secondIssuing).failsWithin(TIMEOUT)
                                     .withThrowableOfType(ExecutionException.class)
                                     .withCauseInstanceOf(EntityMissingAfterFirstEventException.class);
            assertThat(commandGateway.send(new DescribeCard("cardId"), String.class))
                    .succeedsWithin(TIMEOUT).isEqualTo("absent");
        }
    }

    @Nested
    class NothingCanCreateTheEntity {

        @EventSourcedEntity(tagKey = "cardId")
        public static class GiftCard {

            private int amount;

            @EventSourcingHandler
            void on(CardIssued event) {
                this.amount = event.amount();
            }
        }

        public static class Handlers {

            @CommandHandler
            public void handle(IssueCard command, @InjectEntity @Nullable GiftCard card, EventAppender appender) {
                appender.append(new CardIssued(command.cardId(), command.amount()));
            }
        }

        @Test
        void startupFailsWhenEntityHasNeitherEntityCreatorNorStaticEventSourcingHandler() {
            assertThatThrownBy(() -> start(new Handlers(), GiftCard.class))
                    .rootCause()
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("No @EntityCreator present on entity of type")
                    .hasMessageContaining("nor a static @EventSourcingHandler that could create it");
        }
    }
}
