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

package org.axonframework.test.fixture;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.junit.jupiter.api.*;

/**
 * Verifies that an entity declaring a {@link CommandHandlerInterceptor} can still handle its creational commands.
 * <p>
 * An interceptor declared as an instance method needs an entity instance to be invoked on, which does not exist while a
 * creational command is being handled. Such an interceptor must therefore be left out of creational dispatch rather
 * than invoked without a target, while a {@code static} interceptor -- which needs no instance -- still takes part.
 */
class EntityCommandHandlerInterceptorCreationalCommandTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = AxonTestFixture.with(
                EventSourcingConfigurer.create()
                                       .registerEntity(EventSourcedEntityModule.autodetected(String.class,
                                                                                             GiftCard.class))
        );
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    @Test
    void creationalCommandSucceedsWhileEntityDeclaresInstanceInterceptor() {
        // given no prior activity, so no gift card exists yet
        // when issuing a card, which is handled without an entity instance
        // then the instance interceptor is left out and the creational handler runs
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new IssueGiftCard("card-1"))
               .then()
               .success()
               .events(new GiftCardIssued("card-1"));
    }

    @Test
    void instanceInterceptorGuardsInstanceCommandsOnceCardExists() {
        // given an issued and redeemed card
        // when redeeming it again
        // then the instance interceptor rejects the command
        fixture.given()
               .command(new IssueGiftCard("card-1"))
               .command(new RedeemGiftCard("card-1"))
               .when()
               .command(new RedeemGiftCard("card-1"))
               .then()
               .exception(IllegalStateException.class, "Gift card already redeemed")
               .noEvents();
    }

    @Test
    void instanceInterceptorAllowsInstanceCommandWhileCardIsNotRedeemed() {
        // given an issued card
        // when redeeming it
        // then the instance interceptor lets the command through
        fixture.given()
               .command(new IssueGiftCard("card-1"))
               .when()
               .command(new RedeemGiftCard("card-1"))
               .then()
               .success()
               .events(new GiftCardRedeemed("card-1"));
    }

    @EventSourcedEntity(tagKey = "cardId")
    @SuppressWarnings("unused")
    static class GiftCard {

        private boolean redeemed;

        @CommandHandler
        static void handle(IssueGiftCard command, EventAppender appender) {
            appender.append(new GiftCardIssued(command.cardId()));
        }

        @EntityCreator
        GiftCard(GiftCardIssued event) {
        }

        @CommandHandlerInterceptor
        void rejectIfAlreadyRedeemed(CommandMessage command) {
            if (redeemed) {
                throw new IllegalStateException("Gift card already redeemed");
            }
        }

        @CommandHandler
        void handle(RedeemGiftCard command, EventAppender appender) {
            appender.append(new GiftCardRedeemed(command.cardId()));
        }

        @EventSourcingHandler
        void on(GiftCardRedeemed event) {
            this.redeemed = true;
        }
    }

    @Command
    record IssueGiftCard(@TargetEntityId String cardId) {

    }

    @Command
    record RedeemGiftCard(@TargetEntityId String cardId) {

    }

    @Event
    record GiftCardIssued(@EventTag(key = "cardId") String cardId) {

    }

    @Event
    record GiftCardRedeemed(@EventTag(key = "cardId") String cardId) {

    }
}
