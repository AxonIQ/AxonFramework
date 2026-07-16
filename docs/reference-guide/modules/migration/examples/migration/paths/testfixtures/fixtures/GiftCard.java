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
package migration.paths.testfixtures.fixtures;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

/**
 * Shared domain fixture reused across the test-fixtures.adoc samples. Not shown in the rendered documentation.
 */
@EventSourcedEntity(tagKey = "cardId")
public class GiftCard {

    private String cardId;
    private int balance;

    @EntityCreator
    protected GiftCard(@InjectEntityId String cardId) {
        this.cardId = cardId;
    }

    @CommandHandler
    public String handle(IssueCardCommand command, EventAppender eventAppender) {
        eventAppender.append(new CardIssuedEvent(command.cardId(), command.amount()));
        return command.cardId();
    }

    @CommandHandler
    public void handle(RedeemCardCommand command, EventAppender eventAppender) {
        if (command.amount() > balance) {
            throw new IllegalStateException("Insufficient balance on card " + cardId);
        }
        if (command.amount() > 0) {
            eventAppender.append(new CardRedeemedEvent(cardId, command.amount()));
        }
    }

    @CommandHandler
    public void handle(ReimburseCardCommand command, EventAppender eventAppender) {
        eventAppender.append(new CardReimbursedEvent(cardId, command.amount()));
    }

    @EventSourcingHandler
    void on(CardIssuedEvent event) {
        this.cardId = event.cardId();
        this.balance = event.amount();
    }

    @EventSourcingHandler
    void on(CardRedeemedEvent event) {
        this.balance -= event.amount();
    }

    @EventSourcingHandler
    void on(CardReimbursedEvent event) {
        this.balance += event.amount();
    }
}
