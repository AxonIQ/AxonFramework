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
package events.eventhandlers.sealed;

import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

// tag::sealed-events[]
sealed interface AccountEvent permits AccountOpened, MoneyDeposited {
}

@Event(namespace = "bank")
record AccountOpened(String accountId) implements AccountEvent {
}

record MoneyDeposited(String accountId, int amount) implements AccountEvent {
}
// end::sealed-events[]

// tag::sealed-nested-events[]
@Namespace("bank")
sealed interface CardEvent permits CardEvent.Issued, CardEvent.Blocked {

    @Event
    record Issued(String cardId) implements CardEvent {
    }

    @Event
    record Blocked(String cardId) implements CardEvent {
    }
}
// end::sealed-nested-events[]

class SealedEventHandlers {

    // tag::sealed-handler[]
    @EventHandler
    public void on(AccountEvent event) {
        switch (event) {
            case AccountOpened opened -> { /* ... */ }
            case MoneyDeposited deposited -> { /* ... */ }
        }
    }
    // end::sealed-handler[]

    // tag::sealed-handler-forms[]
    @EventHandler
    public void onEach(AccountEvent event) {
        // "bank.AccountOpened" and the class-based name of MoneyDeposited
    }

    @EventHandler(namespace = "legacy")
    public void onEachLegacy(AccountEvent event) {
        // "legacy.AccountOpened" and "legacy.MoneyDeposited"
    }

    @EventHandler
    @EventHandler(namespace = "legacy")
    public void onEachCurrentOrLegacy(AccountEvent event) {
        // All four names
    }

    @EventHandler(eventName = "legacy.AccountChanged")
    public void onSingleName(AccountEvent event) {
        // One name only, no expansion
    }
    // end::sealed-handler-forms[]

    // tag::sealed-renamed-event[]
    @EventHandler
    public void onAccount(AccountEvent event) {
        // ...
    }

    @EventHandler(eventName = "legacy.AccountOpened")
    public void onLegacyAccountOpened(AccountOpened event) {
        onAccount(event);
    }
    // end::sealed-renamed-event[]
}
