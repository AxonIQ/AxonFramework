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
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.ManagedEntity;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Tests that annotated entities with a class hierarchy are sourced correctly when loaded from a real
 * {@link org.axonframework.eventsourcing.EventSourcingRepository}.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
class AnnotatedEntityRepositoryLoadTest {

    private static final String ID = "id-1";

    private record Deposited(@EventTag(key = "Wallet") String id, int amount) {

    }

    @EventSourcedEntity(tagKey = "Wallet", concreteTypes = BasicWallet.class)
    abstract static class Wallet {

        int balance;

        @EntityCreator
        static Wallet create() {
            return new BasicWallet();
        }

        @EventSourcingHandler
        void on(Deposited event) {
            balance += event.amount();
        }
    }

    static class BasicWallet extends Wallet {

    }

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

    @Test
    void loadsPolymorphicEntityApplyingEachEventOnce() {
        // given
        var module = EventSourcedEntityModule.autodetected(String.class, Wallet.class);

        // when
        var wallet = publishThenLoad(module, Wallet.class, new Deposited(ID, 10));

        // then
        assertThat(wallet).isInstanceOf(BasicWallet.class);
        assertThat(wallet.balance).isEqualTo(10);
    }
}
