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

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.giftcard.commands.IssueCardCommand;
import org.axonframework.integrationtests.testsuite.giftcard.commands.RedeemCardCommand;
import org.axonframework.integrationtests.testsuite.giftcard.state.GiftCardEventCreator;
import org.axonframework.integrationtests.testsuite.giftcard.state.GiftCardEventCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.GiftCardIdCreator;
import org.axonframework.integrationtests.testsuite.giftcard.state.GiftCardIdCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.GiftCardIdFactoryMethodCreator;
import org.axonframework.integrationtests.testsuite.giftcard.state.GiftCardIdFactoryMethodCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.GiftCardNoArgCreator;
import org.axonframework.integrationtests.testsuite.giftcard.state.GiftCardNoArgCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.NullableGiftCardEventCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.OptionalGiftCardEventCreatorStateful;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating entity creation flows for creational and instance command handlers under the two existence
 * models an {@link org.axonframework.eventsourcing.annotation.reflection.EntityCreator} expresses:
 * <ul>
 *     <li><b>Always-exists</b> -- a no-argument or identifier-based creator always constructs the entity, even
 *     without a preceding event. The entity is therefore never {@code null}: a non-{@code @Nullable}
 *     {@link InjectEntity} parameter always resolves, and the entity guards its own lifecycle (rejecting a redemption
 *     on a card that has not been issued yet) rather than relying on an {@link EntityNotFoundException}.</li>
 *     <li><b>Exists-after-first-event</b> -- an event-based creator returns {@code null} until its first event. A
 *     non-{@code @Nullable} {@link InjectEntity} parameter then propagates an {@link EntityNotFoundException}, while a
 *     {@code @Nullable} or {@code Optional} parameter supports a create-if-missing flow.</li>
 * </ul>
 *
 * @author Steven van Beelen
 * @author Mateusz Nowak
 */
class EntityCreationTest {

    private EventSourcingConfigurer configurer;

    private CommandGateway commandGateway;

    @BeforeEach
    void setUp() {
        configurer = EventSourcingConfigurer.create();
    }

    private void startFor(EventSourcedEntityModule<?, ?> eventSourcedEntityModule) {
        startFor(null, eventSourcedEntityModule);
    }

    private void startFor(@Nullable CommandHandlingModule commandHandlingModule,
                          EventSourcedEntityModule<?, ?> eventSourcedEntityModule) {
        if (commandHandlingModule != null) {
            configurer = configurer.registerCommandHandlingModule(commandHandlingModule);
        }
        configurer = configurer.registerEntity(eventSourcedEntityModule);
        AxonConfiguration config = configurer.start();
        commandGateway = config.getComponent(CommandGateway.class);
    }

    @Nested
    class NoArgEntityCreationInstanceCommandHandler {

        @BeforeEach
        void setUp() {
            startFor(EventSourcedEntityModule.autodetected(String.class, GiftCardNoArgCreator.class));
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));

            result = commandGateway.send(new RedeemCardCommand("cardId", 100), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }

        @Test
        void instanceCommandBeforeCreationFailsBecauseCardNotIssued() {
            // The no-arg creator always constructs the (empty) entity, so instead of an EntityNotFoundException the
            // entity's own invariant rejects the redemption on a card that has not been issued yet.
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).failsWithin(Duration.ofSeconds(2))
                              .withThrowableOfType(ExecutionException.class)
                              .havingCause()
                              .withMessageContaining("does not exist");
        }
    }

    @Nested
    class IdentifierEntityCreationInstanceCommandHandler {

        @BeforeEach
        void setUp() {
            startFor(EventSourcedEntityModule.autodetected(String.class, GiftCardIdCreator.class));
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));

            result = commandGateway.send(new RedeemCardCommand("cardId", 100), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }

        @Test
        void instanceCommandBeforeCreationFailsBecauseCardNotIssued() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).failsWithin(Duration.ofSeconds(2))
                              .withThrowableOfType(ExecutionException.class)
                              .havingCause()
                              .withMessageContaining("does not exist");
        }
    }

    @Nested
    class IdentifierFactoryMethodEntityCreationInstanceCommandHandler {

        @BeforeEach
        void setUp() {
            startFor(EventSourcedEntityModule.autodetected(String.class, GiftCardIdFactoryMethodCreator.class));
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));

            result = commandGateway.send(new RedeemCardCommand("cardId", 100), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }

        @Test
        void instanceCommandBeforeCreationFailsBecauseCardNotIssued() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).failsWithin(Duration.ofSeconds(2))
                              .withThrowableOfType(ExecutionException.class)
                              .havingCause()
                              .withMessageContaining("does not exist");
        }
    }

    @Nested
    class EventEntityCreationInstanceCommandHandler {

        @BeforeEach
        void setUp() {
            startFor(EventSourcedEntityModule.autodetected(String.class, GiftCardEventCreator.class));
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));

            result = commandGateway.send(new RedeemCardCommand("cardId", 100), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }

        @Test
        void creationsThrowsWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            // The event-based creator returns null until the first event, so the entity does not exist yet.
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).failsWithin(Duration.ofSeconds(2))
                              .withThrowableOfType(ExecutionException.class)
                              .withCauseInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    class NoArgNonNullEntityCreationStatefulCommandHandler {

        @BeforeEach
        void setUp() {
            CommandHandlingModule commandHandlingModule =
                    CommandHandlingModule.named("GiftCardNoArgCreatorStateful")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(c -> new GiftCardNoArgCreatorStateful())
                                         .build();
            EventSourcedEntityModule<String, GiftCardNoArgCreatorStateful.GiftCard> eventSourcedEntityModule =
                    EventSourcedEntityModule.autodetected(String.class, GiftCardNoArgCreatorStateful.GiftCard.class);

            startFor(commandHandlingModule, eventSourcedEntityModule);
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            // The no-arg creator always constructs the entity, so the non-null @InjectEntity resolves and the handler
            // issues the not-yet-issued card.
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));

            result = commandGateway.send(new RedeemCardCommand("cardId", 100), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }

        @Test
        void instanceCommandBeforeCreationFailsBecauseCardNotIssued() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).failsWithin(Duration.ofSeconds(2))
                              .withThrowableOfType(ExecutionException.class)
                              .havingCause()
                              .withMessageContaining("does not exist");
        }
    }

    @Nested
    class IdentifierNonNullEntityCreationStatefulCommandHandler {

        @BeforeEach
        void setUp() {
            CommandHandlingModule commandHandlingModule =
                    CommandHandlingModule.named("GiftCardIdCreatorStateful")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(c -> new GiftCardIdCreatorStateful())
                                         .build();
            EventSourcedEntityModule<String, GiftCardIdCreatorStateful.GiftCard> eventSourcedEntityModule =
                    EventSourcedEntityModule.autodetected(String.class, GiftCardIdCreatorStateful.GiftCard.class);

            startFor(commandHandlingModule, eventSourcedEntityModule);
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));

            result = commandGateway.send(new RedeemCardCommand("cardId", 100), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }

        @Test
        void instanceCommandBeforeCreationFailsBecauseCardNotIssued() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).failsWithin(Duration.ofSeconds(2))
                              .withThrowableOfType(ExecutionException.class)
                              .havingCause()
                              .withMessageContaining("does not exist");
        }
    }

    @Nested
    class IdentifierFactoryMethodNonNullEntityCreationStatefulCommandHandler {

        @BeforeEach
        void setUp() {
            CommandHandlingModule commandHandlingModule =
                    CommandHandlingModule.named("GiftCardIdFactoryMethodCreatorStateful")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(
                                                 c -> new GiftCardIdFactoryMethodCreatorStateful()
                                         )
                                         .build();
            EventSourcedEntityModule<String, GiftCardIdFactoryMethodCreatorStateful.GiftCard> eventSourcedEntityModule =
                    EventSourcedEntityModule.autodetected(String.class,
                                                          GiftCardIdFactoryMethodCreatorStateful.GiftCard.class);

            startFor(commandHandlingModule, eventSourcedEntityModule);
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));

            result = commandGateway.send(new RedeemCardCommand("cardId", 100), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }

        @Test
        void instanceCommandBeforeCreationFailsBecauseCardNotIssued() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).failsWithin(Duration.ofSeconds(2))
                              .withThrowableOfType(ExecutionException.class)
                              .havingCause()
                              .withMessageContaining("does not exist");
        }
    }

    @Nested
    class EventNonNullEntityCreationStatefulCommandHandler {

        @BeforeEach
        void setUp() {
            CommandHandlingModule commandHandlingModule =
                    CommandHandlingModule.named("GiftCardEventCreatorStateful")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(c -> new GiftCardEventCreatorStateful())
                                         .build();
            EventSourcedEntityModule<String, GiftCardEventCreatorStateful.GiftCard> eventSourcedEntityModule =
                    EventSourcedEntityModule.autodetected(String.class, GiftCardEventCreatorStateful.GiftCard.class);

            startFor(commandHandlingModule, eventSourcedEntityModule);
        }

        @Test
        void creationsThrowsWhenCreateCommandComesSinceNonNullEntityIsExpected() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).failsWithin(Duration.ofSeconds(2))
                              .withThrowableOfType(ExecutionException.class)
                              .withCauseInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void creationsThrowsWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).failsWithin(Duration.ofSeconds(2))
                              .withThrowableOfType(ExecutionException.class)
                              .withCauseInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    class EventNullableEntityCreationStatefulCommandHandler {

        @BeforeEach
        void setUp() {
            CommandHandlingModule commandHandlingModule =
                    CommandHandlingModule.named("NullableGiftCardEventCreatorStateful")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(
                                                 c -> new NullableGiftCardEventCreatorStateful()
                                         )
                                         .build();
            EventSourcedEntityModule<String, NullableGiftCardEventCreatorStateful.GiftCard> eventSourcedEntityModule =
                    EventSourcedEntityModule.autodetected(String.class,
                                                          NullableGiftCardEventCreatorStateful.GiftCard.class);

            startFor(commandHandlingModule, eventSourcedEntityModule);
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }

        @Test
        void creationsIsSuccessfulWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }
    }

    @Nested
    class EventOptionalEntityCreationStatefulCommandHandler {

        @BeforeEach
        void setUp() {
            CommandHandlingModule commandHandlingModule =
                    CommandHandlingModule.named("OptionalGiftCardEventCreatorStateful")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(
                                                 c -> new OptionalGiftCardEventCreatorStateful()
                                         )
                                         .build();
            EventSourcedEntityModule<String, OptionalGiftCardEventCreatorStateful.GiftCard> eventSourcedEntityModule =
                    EventSourcedEntityModule.autodetected(String.class,
                                                          OptionalGiftCardEventCreatorStateful.GiftCard.class);

            startFor(commandHandlingModule, eventSourcedEntityModule);
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }

        @Test
        void creationsIsSuccessfulWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).succeedsWithin(Duration.ofSeconds(2));
        }
    }
}
