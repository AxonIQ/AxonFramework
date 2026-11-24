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
package commands.entities.eventsourcedentity.sealed;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.messaging.eventhandling.annotation.Event;

// tag::sealed-entity[]
sealed interface AccountEvent permits AccountOpened, MoneyDeposited {
}

@Event(namespace = "bank")
record AccountOpened(String accountId) implements AccountEvent {
}

@Event(namespace = "bank")
record MoneyDeposited(String accountId, int amount) implements AccountEvent {
}

record Account(String accountId, int balance) {

    @EventSourcingHandler
    Account evolve(AccountEvent event) {
        return switch (event) {
            case AccountOpened opened -> new Account(opened.accountId(), 0);
            case MoneyDeposited deposited -> new Account(accountId, balance + deposited.amount());
        };
    }
}
// end::sealed-entity[]
