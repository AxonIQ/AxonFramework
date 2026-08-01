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

/**
 * An entity-centric command handler for which the entity is created based on the identifier through a static factory
 * {@code @EntityCreator} method (rather than a constructor), will fail on instance command handlers with an
 * {@link org.axonframework.modelling.repository.EntityNotFoundException}.
 * <p>
 * This mirrors {@link GiftCardIdCreator}, but proves the no-arg/id-only not-found guard applies equally to method-based
 * creators, not just constructors.
 *
 * @author Steven van Beelen
 */
@EventSourcedEntity(tagKey = "cardId")
public class GiftCardIdFactoryMethodCreator {

    private String cardId;
    private double amount;

    private GiftCardIdFactoryMethodCreator(String cardId) {
        this.cardId = cardId;
    }

    @EntityCreator
    public static GiftCardIdFactoryMethodCreator create(@InjectEntityId String cardId) {
        return new GiftCardIdFactoryMethodCreator(cardId);
    }

    @CommandHandler
    public static void handle(IssueCardCommand command, EventAppender appender) {
        appender.append(new CardIssuedEvent(command.cardId(), command.amount()));
    }

    @CommandHandler
    public void handle(RedeemCardCommand command, EventAppender appender) {
        if (amount - command.amount() < 0) {
            throw new IllegalStateException("Insufficient funds");
        }
        appender.append(new CardRedeemedEvent(cardId, command.amount()));
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
