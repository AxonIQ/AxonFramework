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
package commands.entities.eventsourcedentity.hierarchy.immutable;

import commands.entities.eventsourcedentity.Deposited;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;

// tag::immutable[]
interface Card {

    int balance();

    Card withBalance(int balance);

    @EventSourcingHandler
    default Card onDeposit(Deposited event) {
        return withBalance(balance() + event.amount());
    }
}

record VipCard(int balance) implements Card {

    public Card withBalance(int balance) {
        return new VipCard(balance);
    }

    @EventSourcingHandler
    VipCard onVipDeposit(Deposited event) {
        return new VipCard(balance * 2);
    }
}
// end::immutable[]
