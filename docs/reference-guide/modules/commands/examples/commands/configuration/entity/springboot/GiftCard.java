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
package commands.configuration.entity.springboot;

import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

// tag::spring-event-sourced-entity[]
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;

@EventSourced
public class GiftCard {

    private String id;
    private int remainingValue;

    @CommandHandler
    public static GiftCard handle(IssueCardCommand cmd, EventAppender eventAppender) {
        GiftCard card = new GiftCard();
        eventAppender.append(new CardIssuedEvent(cmd.cardId(), cmd.amount()));
        return card;
    }

    @CommandHandler
    public void handle(RedeemCardCommand cmd, EventAppender eventAppender) {
        if (remainingValue >= cmd.amount()) {
            eventAppender.append(new CardRedeemedEvent(id, cmd.amount()));
        }
    }

    @EventSourcingHandler
    private void on(CardIssuedEvent event) {
        this.id = event.cardId();
        this.remainingValue = event.amount();
    }

    @EventSourcingHandler
    private void on(CardRedeemedEvent event) {
        this.remainingValue -= event.amount();
    }

    @EntityCreator
    protected GiftCard() {
        // Required no-arg constructor for reconstitution
    }
}
// end::spring-event-sourced-entity[]
