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
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.entity.annotation.EntityMember;

import java.util.ArrayList;
import java.util.List;

@EventSourcedEntity(tagKey = "accountId")
public class Account {

    @EntityMember(routingKey = "cardId")
    private final List<Card> cards = new ArrayList<>();

    @CommandHandler
    void handle(WithdrawMoney command, EventAppender eventAppender) {
        eventAppender.append(new MoneyWithdrawn(command.accountId(), command.amount()));
    }

    @AppendCriteriaBuilder
    static EventCriteria appendCriteria(WithdrawMoney command, EventCriteria sourcingCriteria) {
        return sourcingCriteria.restrictToEventTypes(MoneyWithdrawn.class.getName());
    }

    static class Card {

        @CommandHandler
        void handle(ReplaceCard command, EventAppender eventAppender) {
            eventAppender.append(new CardReplaced(command.accountId(), command.cardId()));
        }

        @AppendCriteriaBuilder
        static EventCriteria appendCriteria(ReplaceCard command, EventCriteria sourcingCriteria) {
            return sourcingCriteria.restrictToEventTypes(CardReplaced.class.getName());
        }
    }
}
// end::entity-owned-append-criteria[]

record WithdrawMoney(String accountId, int amount) {
}

record ReplaceCard(String accountId, String cardId) {
}

record MoneyWithdrawn(String accountId, int amount) {
}

record CardReplaced(String accountId, String cardId) {
}
