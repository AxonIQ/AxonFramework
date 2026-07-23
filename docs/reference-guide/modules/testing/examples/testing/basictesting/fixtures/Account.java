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
package testing.basictesting.fixtures;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

/**
 * Shared domain fixture reused across the basic-testing.adoc samples. Not shown in the rendered documentation.
 * <p>
 * A purely event-sourced entity, used in the samples that register command handling separately (either as a
 * dedicated stateful command handling component, or via an entity subclass that adds its own command handlers).
 */
@EventSourcedEntity(tagKey = "accountId")
public class Account {

    private String accountId;
    private double balance;
    private boolean closed;

    @EntityCreator
    public Account() {
    }

    @EventSourcingHandler
    void on(AccountCreatedEvent event) {
        this.accountId = event.accountId();
        this.balance = event.balance();
    }

    @EventSourcingHandler
    void on(MoneyDepositedEvent event) {
        this.balance += event.amount();
    }

    @EventSourcingHandler
    void on(MoneyWithdrawnEvent event) {
        this.balance -= event.amount();
    }

    @EventSourcingHandler
    void on(AccountClosedEvent event) {
        this.closed = true;
    }

    public String accountId() {
        return accountId;
    }

    public double balance() {
        return balance;
    }

    public boolean closed() {
        return closed;
    }
}
