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
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.modelling.StateEvolvingException;
import org.axonframework.modelling.StateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that an event sourcing handler returning a subclass the entity model does not know fails clearly instead of
 * silently dropping every later event.
 *
 * @author Rohit Wadhwa
 */
class UnknownSubclassEventSourcingHandlerTest {

    private AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Test
    void loadingFailsWhenHandlerReturnsSubclassNotListedAsConcreteType() {
        // given
        configuration = EventSourcingConfigurer.create()
                                               .registerEntity(EventSourcedEntityModule.autodetected(
                                                       String.class,
                                                       Wallet.class
                                               ))
                                               .start();
        EventStore eventStore = configuration.getComponent(EventStore.class);
        eventStore.publish(
                null,
                List.of(
                        new GenericEventMessage(new MessageType(Opened.class), new Opened("w1")),
                        new GenericEventMessage(new MessageType(Deposited.class), new Deposited("w1", 10))
                )
        ).join();

        // when / then
        var repository = configuration.getComponent(StateManager.class).repository(Wallet.class, String.class);
        var unitOfWork = configuration.getComponent(UnitOfWorkFactory.class).create();
        assertThatThrownBy(() -> unitOfWork.executeWithResult(context -> repository.load("w1", context)).join())
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
