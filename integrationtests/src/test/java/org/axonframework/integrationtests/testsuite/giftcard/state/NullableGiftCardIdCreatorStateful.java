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

package org.axonframework.integrationtests.testsuite.giftcard.state;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.integrationtests.testsuite.giftcard.commands.IssueCardCommand;
import org.axonframework.integrationtests.testsuite.giftcard.commands.RedeemCardCommand;
import org.axonframework.integrationtests.testsuite.giftcard.events.CardIssuedEvent;
import org.axonframework.integrationtests.testsuite.giftcard.events.CardRedeemedEvent;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;

/**
 * A stateful command handler for which the entity is created based on the identifier, will succeed for instance command
 * handlers because the handler lives outside the entity and receives a {@code null} entity when it does not exist yet.
 * <p>
 * Although the command defines the identifier and the constructor could produce an entity without any preceding event,
 * the {@code @InjectEntity} parameter is annotated {@code @Nullable} here, so it consistently resolves to {@code null}
 * when there is no event to source the entity from, just as the no-arg and event-based creator styles do.
 *
 * @author Steven van Beelen
 */
public class NullableGiftCardIdCreatorStateful {

    @CommandHandler
    public void handle(IssueCardCommand command,
                       @InjectEntity @Nullable GiftCard entity,
                       EventAppender appender) {
        if (entity == null) {
            appender.append(new CardIssuedEvent(command.cardId(), command.amount()));
        } else {
            throw new IllegalStateException("GiftCard for id [" + command.cardId() + "] already exists");
        }
    }

    @CommandHandler
    public void handle(RedeemCardCommand command,
                       @InjectEntity @Nullable GiftCard entity,
                       EventAppender appender) {
        if (entity == null) {
            appender.append(new CardIssuedEvent(command.cardId(), 9001));
        }
        if (entity != null && entity.amount - command.amount() < 0) {
            throw new IllegalStateException("Insufficient funds");
        }
        appender.append(new CardRedeemedEvent(command.cardId(), command.amount()));
    }

    @EventSourcedEntity(tagKey = "cardId")
    public static class GiftCard {

        String cardId;
        double amount;

        @EntityCreator
        public GiftCard(@InjectEntityId String cardId) {
            this.cardId = cardId;
            this.amount = 9001;
        }

        @EventSourcingHandler
        public void on(CardIssuedEvent event) {
            cardId = event.cardId();
            amount = event.amount();
        }

        @EventSourcingHandler
        public void on(CardRedeemedEvent event) {
            amount = amount - event.amount();
        }
    }
}
