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
import org.axonframework.integrationtests.testsuite.giftcard.commands.IssueCardWithInitialRedemptionCommand;
import org.axonframework.integrationtests.testsuite.giftcard.commands.RedeemCardCommand;
import org.axonframework.integrationtests.testsuite.giftcard.events.CardIssuedEvent;
import org.axonframework.integrationtests.testsuite.giftcard.events.CardRedeemedEvent;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.repository.ManagedEntity;
import org.jspecify.annotations.Nullable;

/**
 * A stateful command handler for which the entity is created based on the identifier, will succeed for instance command
 * handlers because the handler lives outside the entity and receives an entity with a {@code null} state when it does
 * not exist yet. The type is deliberately wrapped in a {@link ManagedEntity} to validate nullability support on said
 * {@code ManagedEntity} as well.
 * <p>
 * {@link #handle(IssueCardWithInitialRedemptionCommand, ManagedEntity, EventAppender)} additionally validates a
 * create-if-missing flow: it appends a creation event, then relies on the injected {@link ManagedEntity} having
 * observed that same event's effect - through its live subscription - before deciding on and appending a second,
 * dependent event within the same command handling.
 *
 * @author Steven van Beelen
 */
public class NullableGiftCardIdCreatorStateful {

    @CommandHandler
    public void handle(IssueCardCommand command,
                       @InjectEntity ManagedEntity<String, GiftCard> entity,
                       EventAppender appender) {
        if (entity.entity() == null) {
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

    /**
     * Create-if-missing in a single command handler invocation: issues the card, then redeems part of it right away.
     * <p>
     * The insufficient-funds check for the redemption reads {@code entity.entity()} again after appending the
     * {@link CardIssuedEvent}. This only works because the injected {@code entity} is subscribed to live updates: the
     * first {@code appender.append(...)} call evolves this very {@link ManagedEntity} in place before this method
     * continues, so the second event's decision is based on the state the first event just established, rather than on
     * the (already stale) {@code null} the parameter was originally resolved with.
     */
    @CommandHandler
    public void handle(IssueCardWithInitialRedemptionCommand command,
                       @InjectEntity ManagedEntity<String, GiftCard> entity,
                       EventAppender appender) {
        if (entity.entity() != null) {
            throw new IllegalStateException("GiftCard for id [" + command.cardId() + "] already exists");
        }
        appender.append(new CardIssuedEvent(command.cardId(), command.amount()));

        GiftCard issued = entity.entity();
        if (issued == null) {
            throw new IllegalStateException(
                    "GiftCard for id [" + command.cardId() + "] was not created as expected"
            );
        }
        if (issued.amount - command.initialRedemption() < 0) {
            throw new IllegalStateException("Insufficient funds");
        }
        appender.append(new CardRedeemedEvent(command.cardId(), command.initialRedemption()));
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
