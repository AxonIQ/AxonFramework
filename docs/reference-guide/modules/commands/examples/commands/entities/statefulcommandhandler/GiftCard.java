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
package commands.entities.statefulcommandhandler;

// tag::giftcard-entity[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

@EventSourcedEntity(tagKey = "cardId")
public class GiftCard {

    private String cardId;
    private double amount;

    @EntityCreator
    public GiftCard() {}

    @EventSourcingHandler
    void on(CardIssued event) {
        this.cardId = event.cardId();
        this.amount = event.amount();
    }

    @EventSourcingHandler
    void on(CardRedeemed event) {
        this.amount -= event.amount();
    }

    String cardId() { return cardId; }
    double amount() { return amount; }
}
// end::giftcard-entity[]
