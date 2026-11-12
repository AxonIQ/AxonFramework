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

package org.axonframework.eventsourcing.configuration;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.modelling.StateEvolvingException;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.ManagedEntity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Tests that an event sourcing handler returning a subclass the entity model does not know fails clearly, and that
 * returning a registered concrete type or a sealed subtype still evolves later events.
 *
 * @author Rohit Wadhwa
 */
class UnknownSubclassEventSourcingHandlerTest {

    private static final String ID = "w1";

    private static <E> E publishThenLoad(EventSourcedEntityModule<String, E> module,
                                         Class<E> entityType,
                                         Object... payloads) {
        AxonConfiguration configuration = EventSourcingConfigurer.create()
                                                                 .registerEntity(module)
                                                                 .start();
        try {
            var unitOfWorkFactory = configuration.getComponent(UnitOfWorkFactory.class);
            var eventStore = configuration.getComponent(EventStore.class);
            unitOfWorkFactory.create().executeWithResult(context -> {
                var events = Arrays.stream(payloads).map(payload -> asEventMessage(payload)).toList();
                return eventStore.publish(context, events);
            }).orTimeout(10, TimeUnit.SECONDS).join();
            return unitOfWorkFactory.create().executeWithResult(
                    context -> configuration.getComponent(StateManager.class)
                                            .repository(entityType, String.class)
                                            .load(ID, context)
                                            .thenApply(ManagedEntity::entity)
            ).orTimeout(10, TimeUnit.SECONDS).join();
        } finally {
            configuration.shutdown();
        }
    }

    @Nested
    class UnlistedSubclass {

        @Test
        void loadingFailsWhenHandlerReturnsSubclassNotListedAsConcreteType() {
            // given
            var module = EventSourcedEntityModule.autodetected(String.class, Wallet.class);

            // when / then
            assertThatThrownBy(() -> publishThenLoad(
                    module,
                    Wallet.class,
                    new Opened(ID),
                    new Deposited(ID, 10)
            ))
                    .hasRootCauseInstanceOf(StateEvolvingException.class)
                    .rootCause()
                    .hasMessageContaining("UnknownWallet")
                    .hasMessageContaining(Wallet.class.getName())
                    .hasMessageContaining("concreteTypes")
                    .hasMessageContaining("sealed");
        }

        record Opened(@EventTag(key = "walletId") String walletId) {

        }

        record Deposited(@EventTag(key = "walletId") String walletId, int amount) {

        }

        @EventSourcedEntity(tagKey = "walletId")
        static class Wallet {

            int balance;

            @EntityCreator
            public Wallet() {
            }

            @EventSourcingHandler
            Wallet on(Opened event) {
                return new UnknownWallet();
            }

            @EventSourcingHandler
            void on(Deposited event) {
                balance += event.amount();
            }
        }

        static class UnknownWallet extends Wallet {

        }
    }

    @Nested
    class ListedConcreteTypes {

        @Test
        void loadingEvolvesIntoRegisteredConcreteTypeAndAppliesLaterEvents() {
            // given
            var module = EventSourcedEntityModule.autodetected(String.class, Wallet.class);

            // when
            Wallet wallet = publishThenLoad(
                    module,
                    Wallet.class,
                    new Opened(ID),
                    new Deposited(ID, 10)
            );

            // then
            assertThat(wallet).isInstanceOf(OpenWallet.class);
            assertThat(wallet.balance).isEqualTo(10);
        }

        record Opened(@EventTag(key = "listedWalletId") String walletId) {

        }

        record Deposited(@EventTag(key = "listedWalletId") String walletId, int amount) {

        }

        @EventSourcedEntity(tagKey = "listedWalletId", concreteTypes = {DraftWallet.class, OpenWallet.class})
        abstract static class Wallet {

            int balance;

            @EntityCreator
            static Wallet create() {
                return new DraftWallet();
            }

            @EventSourcingHandler
            Wallet on(Opened event) {
                return new OpenWallet();
            }

            @EventSourcingHandler
            void on(Deposited event) {
                balance += event.amount();
            }
        }

        static class DraftWallet extends Wallet {

        }

        static class OpenWallet extends Wallet {

        }
    }

    @Nested
    class SealedHierarchy {

        @Test
        void loadingEvolvesIntoPermittedSubtypeAndAppliesLaterEvents() {
            // given
            var module = EventSourcedEntityModule.autodetected(String.class, Card.class);

            // when
            Card card = publishThenLoad(
                    module,
                    Card.class,
                    new Opened(ID),
                    new Deposited(ID, 10)
            );

            // then
            assertThat(card).isInstanceOf(OpenCard.class);
            assertThat(((OpenCard) card).balance()).isEqualTo(10);
        }

        record Opened(@EventTag(key = "cardId") String cardId) {

        }

        record Deposited(@EventTag(key = "cardId") String cardId, int amount) {

        }

        @EventSourcedEntity(tagKey = "cardId")
        sealed interface Card permits InitialCard, OpenCard {

        }

        record InitialCard() implements Card {

            @EntityCreator
            static Card create() {
                return new InitialCard();
            }

            @EventSourcingHandler
            OpenCard on(Opened event) {
                return new OpenCard(0);
            }
        }

        record OpenCard(int balance) implements Card {

            @EventSourcingHandler
            OpenCard on(Deposited event) {
                return new OpenCard(balance + event.amount());
            }
        }
    }
}
