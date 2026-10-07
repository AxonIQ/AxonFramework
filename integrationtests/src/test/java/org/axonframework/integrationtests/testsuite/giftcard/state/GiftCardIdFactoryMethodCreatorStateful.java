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

/**
 * A stateful command handler whose entity is created based on the identifier through a static factory
 * {@code @EntityCreator} method (rather than a constructor). Because an identifier-based creator always constructs the
 * entity, the non-null {@code @InjectEntity} parameter always resolves to a (possibly empty) entity. The handler
 * therefore guards the lifecycle on the entity's own state, mirroring {@link GiftCardIdCreatorStateful} for a
 * method-based creator.
 *
 * @author Steven van Beelen
 */
public class GiftCardIdFactoryMethodCreatorStateful {

    @CommandHandler
    public void handle(IssueCardCommand command,
                       @InjectEntity GiftCard entity,
                       EventAppender appender) {
        if (entity.issued) {
            throw new IllegalStateException("GiftCard for id [" + command.cardId() + "] already exists");
        }
        appender.append(new CardIssuedEvent(command.cardId(), command.amount()));
    }

    @CommandHandler
    public void handle(RedeemCardCommand command,
                       @InjectEntity GiftCard entity,
                       EventAppender appender) {
        if (!entity.issued) {
            throw new IllegalStateException("GiftCard for id [" + command.cardId() + "] does not exist");
        }
        if (entity.amount - command.amount() < 0) {
            throw new IllegalStateException("Insufficient funds");
        }
        appender.append(new CardRedeemedEvent(entity.cardId, command.amount()));
    }

    @EventSourcedEntity(tagKey = "cardId")
    public static class GiftCard {

        String cardId;
        double amount;
        boolean issued;

        private GiftCard(String cardId, double amount) {
            this.cardId = cardId;
            this.amount = amount;
        }

        @EntityCreator
        public static GiftCard create(@InjectEntityId String cardId) {
            return new GiftCard(cardId, 0);
        }

        @EventSourcingHandler
        public void on(CardIssuedEvent event) {
            cardId = event.cardId();
            amount = event.amount();
            issued = true;
        }

        @EventSourcingHandler
        public void on(CardRedeemedEvent event) {
            amount = amount - event.amount();
        }
    }
}
