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
import org.axonframework.integrationtests.testsuite.giftcard.state.GiftCardNoArgCreator;
import org.axonframework.integrationtests.testsuite.giftcard.state.GiftCardNoArgCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.NullableGiftCardEventCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.NullableGiftCardIdCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.NullableGiftCardNoArgCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.OptionalGiftCardEventCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.OptionalGiftCardIdCreatorStateful;
import org.axonframework.integrationtests.testsuite.giftcard.state.OptionalGiftCardNoArgCreatorStateful;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating all entity creation flows for both creational and instance command handlers. This means there
 * are twelve tests in this class, being:
 *
 * <ol>
 *     <li>Entity-centric, no-arg entity creator, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Entity-centric, no-arg entity creator, instance command handler.
 *     Fails, as the entity wasn't created yet by a creational command handler.
 *     Hence, invoking an instance command handler <b>on</b> an entity that does not exist shouldn't succeed</li>
 *     <li>Entity-centric, id-based entity creator, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Entity-centric, id-based entity creator, instance command handler.
 *     Fails, as the entity wasn't created yet by a creational command handler.
 *     Hence, invoking an instance command handler <b>on</b> an entity that does not exist shouldn't succeed</li>
 *     <li>Entity-centric, first-event-based entity creator, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Entity-centric, first-event-based entity creator, instance command handler.
 *     Fails, as the entity wasn't created yet by a creational command handler.
 *     Hence, invoking an instance command handler <b>on</b> an entity that does not exist shouldn't succeed</li>
 *     <li>Stateful command handler, no-arg entity creator, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Stateful command handler, no-arg entity creator, instance command handler.
 *     Succeeds, as we can (1) create the entity just fine and (2) the command handling happens outside the entity.</li>
 *     <li>Stateful command handler, no-arg entity creator, {@link Nullable} marked, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Stateful command handler, no-arg entity creator, {@code Nullable} marked, instance command handler.
 *     Succeeds, as we can (1) create the entity just fine and (2) the command handling happens outside the entity.</li>
 *     <li>Stateful command handler, id-based entity creator, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Stateful command handler, id-based entity creator, instance command handler.
 *     Succeeds, as we can (1) create the entity just fine and (2) the command handling happens outside the entity.</li>
 *     <li>Stateful command handler, id-based entity creator, {@code Nullable} marked, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Stateful command handler, id-based entity creator, {@code Nullable} marked, instance command handler.
 *     Succeeds, as we can (1) create the entity just fine and (2) the command handling happens outside the entity.</li>
 *     <li>Stateful command handler, first-event-based entity creator, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Stateful command handler, first-event-based entity creator, instance command handler.
 *     Succeeds, as we can (1) return a {@code null} entity and (2) the command handling happens outside the entity.</li>
 *     <li>Stateful command handler, first-event-based entity creator, {@code Nullable} marked, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Stateful command handler, first-event-based entity creator, {@code Nullable} marked, instance command handler.
 *     Succeeds, as we can (1) return a {@code null} entity and (2) the command handling happens outside the entity.</li>
 *     <li>Stateful command handler, no-arg entity creator, {@code Optional}-typed, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Stateful command handler, no-arg entity creator, {@code Optional}-typed, instance command handler.
 *     Succeeds, as we can (1) create the entity just fine and (2) the command handling happens outside the entity.</li>
 *     <li>Stateful command handler, id-based entity creator, {@code Optional}-typed, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Stateful command handler, id-based entity creator, {@code Optional}-typed, instance command handler.
 *     Succeeds, as we can (1) create the entity just fine and (2) the command handling happens outside the entity.</li>
 *     <li>Stateful command handler, first-event-based entity creator, {@code Optional}-typed, creational command handler.
 *     Succeeds, as this is a typical scenario.</li>
 *     <li>Stateful command handler, first-event-based entity creator, {@code Optional}-typed, instance command handler.
 *     Succeeds, as we can (1) return an empty {@code Optional} and (2) the command handling happens outside the entity.</li>
 * </ol>
 * <p>
 * On top of the scenarios above, this class also validates the default nullability contract of {@link InjectEntity},
 * regardless of the creator style: a non-{@code @Nullable}, non-{@code Optional} {@code @InjectEntity} parameter
 * always propagates an {@link EntityNotFoundException} instead of receiving an entity that was implicitly
 * constructed without a preceding event.
 *
 * @author Steven van Beelen
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

            assertThat(result).isNotCompletedExceptionally();

            result = commandGateway.send(new RedeemCardCommand("cardId", 100), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }

        @Test
        void creationsThrowsWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isInstanceOf(EntityNotFoundException.class);
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

            assertThat(result).isNotCompletedExceptionally();

            result = commandGateway.send(new RedeemCardCommand("cardId", 100), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }

        @Test
        void creationsThrowsWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isInstanceOf(EntityNotFoundException.class);
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

            assertThat(result).isNotCompletedExceptionally();

            result = commandGateway.send(new RedeemCardCommand("cardId", 100), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }

        @Test
        void creationsThrowsWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isInstanceOf(EntityNotFoundException.class);
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
        void creationsThrowsWhenCreateCommandComesSinceNonNullEntityIsExpected() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void creationsThrowsWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isInstanceOf(EntityNotFoundException.class);
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
        void creationsThrowsWhenCreateCommandComesSinceNonNullEntityIsExpected() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void creationsThrowsWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isInstanceOf(EntityNotFoundException.class);
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

            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void creationsThrowsWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isCompletedExceptionally();
            assertThat(result.exceptionNow()).isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    class NoArgNullableEntityCreationStatefulCommandHandler {

        @BeforeEach
        void setUp() {
            CommandHandlingModule commandHandlingModule =
                    CommandHandlingModule.named("NullableGiftCardNoArgCreatorStateful")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(
                                                 c -> new NullableGiftCardNoArgCreatorStateful()
                                         )
                                         .build();
            EventSourcedEntityModule<String, NullableGiftCardNoArgCreatorStateful.GiftCard> eventSourcedEntityModule =
                    EventSourcedEntityModule.autodetected(String.class,
                                                          NullableGiftCardNoArgCreatorStateful.GiftCard.class);

            startFor(commandHandlingModule, eventSourcedEntityModule);
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }

        @Test
        void creationsIsSuccessfulWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }
    }

    @Nested
    class IdentifierNullableEntityCreationStatefulCommandHandler {

        @BeforeEach
        void setUp() {
            CommandHandlingModule commandHandlingModule =
                    CommandHandlingModule.named("NullableGiftCardIdCreatorStateful")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(
                                                 c -> new NullableGiftCardIdCreatorStateful()
                                         )
                                         .build();
            EventSourcedEntityModule<String, NullableGiftCardIdCreatorStateful.GiftCard> eventSourcedEntityModule =
                    EventSourcedEntityModule.autodetected(String.class,
                                                          NullableGiftCardIdCreatorStateful.GiftCard.class);

            startFor(commandHandlingModule, eventSourcedEntityModule);
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }

        @Test
        void creationsIsSuccessfulWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isNotCompletedExceptionally();
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

            assertThat(result).isNotCompletedExceptionally();
        }

        @Test
        void creationsIsSuccessfulWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }
    }

    @Nested
    class NoArgOptionalEntityCreationStatefulCommandHandler {

        @BeforeEach
        void setUp() {
            CommandHandlingModule commandHandlingModule =
                    CommandHandlingModule.named("OptionalGiftCardNoArgCreatorStateful")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(
                                                 c -> new OptionalGiftCardNoArgCreatorStateful()
                                         )
                                         .build();
            EventSourcedEntityModule<String, OptionalGiftCardNoArgCreatorStateful.GiftCard> eventSourcedEntityModule =
                    EventSourcedEntityModule.autodetected(String.class,
                                                          OptionalGiftCardNoArgCreatorStateful.GiftCard.class);

            startFor(commandHandlingModule, eventSourcedEntityModule);
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }

        @Test
        void creationsIsSuccessfulWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }
    }

    @Nested
    class IdentifierOptionalEntityCreationStatefulCommandHandler {

        @BeforeEach
        void setUp() {
            CommandHandlingModule commandHandlingModule =
                    CommandHandlingModule.named("OptionalGiftCardIdCreatorStateful")
                                         .commandHandlers()
                                         .autodetectedCommandHandlingComponent(
                                                 c -> new OptionalGiftCardIdCreatorStateful()
                                         )
                                         .build();
            EventSourcedEntityModule<String, OptionalGiftCardIdCreatorStateful.GiftCard> eventSourcedEntityModule =
                    EventSourcedEntityModule.autodetected(String.class,
                                                          OptionalGiftCardIdCreatorStateful.GiftCard.class);

            startFor(commandHandlingModule, eventSourcedEntityModule);
        }

        @Test
        void creationIsSuccessfulWhenCreateCommandComesFirst() {
            CompletableFuture<Void> result = commandGateway.send(new IssueCardCommand("cardId", 1337), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }

        @Test
        void creationsIsSuccessfulWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isNotCompletedExceptionally();
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

            assertThat(result).isNotCompletedExceptionally();
        }

        @Test
        void creationsIsSuccessfulWhenHandlingInstanceCommandBeforeHandlingAnyCreateCommand() {
            CompletableFuture<Void> result = commandGateway.send(new RedeemCardCommand("cardId", 1337), Void.class);

            assertThat(result).isNotCompletedExceptionally();
        }
    }
}
