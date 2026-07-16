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
package migration.paths.aggregates.index.simpleexample;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

// tag::simple-example[]
@EventSourcedEntity // <1>
public class GiftCard {

    private String cardId;
    private int remainingValue;

    @EntityCreator // <2>
    public GiftCard() {
    }

    @CommandHandler // <3>
    public static void handle(IssueCardCommand cmd, EventAppender eventAppender) {
        eventAppender.append(new CardIssuedEvent(cmd.cardId(), cmd.amount()));
    }

    @CommandHandler // <4>
    public void handle(RedeemCardCommand cmd, EventAppender eventAppender) {
        if (cmd.amount() > remainingValue) {
            throw new IllegalStateException("Insufficient funds");
        }
        eventAppender.append(new CardRedeemedEvent(cardId, cmd.amount()));
    }

    @EventSourcingHandler // <5>
    public void on(CardIssuedEvent event) {
        this.cardId = event.cardId();
        this.remainingValue = event.amount();
    }

    @EventSourcingHandler
    public void on(CardRedeemedEvent event) {
        this.remainingValue -= event.amount();
    }
}
// end::simple-example[]
