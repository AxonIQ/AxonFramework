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
package events.eventstoreinternals.appendcriteria.entity;

// tag::entity-owned-append-criteria[]
import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.entity.annotation.EntityMember;

import java.util.ArrayList;
import java.util.List;

@EventSourcedEntity(tagKey = "accountId")
public class Account {

    private int balance = 0;

    @EntityMember(routingKey = "cardId")
    private final List<Card> cards = new ArrayList<>();

    // Sourcing: every event type needed to rebuild the state a decision reads.
    @EventCriteriaBuilder
    static EventCriteria sourcingCriteria(String accountId) {
        return EventCriteria.havingTags(Tag.of("accountId", accountId))
                            .andBeingOneOfTypes(MoneyDeposited.class.getName(),
                                                MoneyWithdrawn.class.getName(),
                                                CardReplaced.class.getName());
    }

    @EventSourcingHandler
    void evolve(MoneyDeposited event) {
        balance += event.amount();
    }

    @EventSourcingHandler
    void evolve(MoneyWithdrawn event) {
        balance -= event.amount();
    }

    @CommandHandler
    void handle(WithdrawMoney command, EventAppender eventAppender) {
        if (balance < command.amount()) {
            throw new IllegalStateException("Insufficient balance");
        }
        eventAppender.append(new MoneyWithdrawn(command.accountId(), command.amount()));
    }

    // Appending: only the events that can invalidate the decision above. A concurrent withdrawal can
    // overdraw the account, a concurrent deposit only ever raises the balance, so deposits are left out.
    @AppendCriteriaBuilder
    static EventCriteria appendCriteria(WithdrawMoney command, EventCriteria sourcingCriteria) {
        return sourcingCriteria.replaceEventTypes(MoneyWithdrawn.class);
    }

    static class Card {

        @CommandHandler
        void handle(ReplaceCard command, EventAppender eventAppender) {
            eventAppender.append(new CardReplaced(command.accountId(), command.cardId()));
        }

        @AppendCriteriaBuilder
        static EventCriteria appendCriteria(ReplaceCard command, EventCriteria sourcingCriteria) {
            return sourcingCriteria.replaceEventTypes(CardReplaced.class);
        }
    }
}
// end::entity-owned-append-criteria[]

record WithdrawMoney(String accountId, int amount) {
}

record ReplaceCard(String accountId, String cardId) {
}

record MoneyDeposited(String accountId, int amount) {
}

record MoneyWithdrawn(String accountId, int amount) {
}

record CardReplaced(String accountId, String cardId) {
}
