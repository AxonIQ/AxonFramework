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

package org.axonframework.integrationtests.testsuite.account.state;

import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.SourcingCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.integrationtests.testsuite.account.commands.CreditAccount;
import org.axonframework.integrationtests.testsuite.account.commands.DebitAccount;
import org.axonframework.integrationtests.testsuite.account.events.AccountCredited;
import org.axonframework.integrationtests.testsuite.account.events.AccountDebited;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

/**
 * An account entity demonstrating asymmetric sourcing and append criteria: sourcing needs every credit and debit to
 * compute the current balance, but only a concurrent debit, never a credit, can invalidate a decision that assumed a
 * certain balance.
 */
@EventSourcedEntity
public class Account {

    private final String id;
    private long balance;

    @EntityCreator
    public Account(@InjectEntityId String id) {
        this.id = id;
    }

    @SourcingCriteriaBuilder
    private static EventCriteria resolveSourcingCriteria(String id) {
        return EventCriteria
                .havingTags(Tag.of("accountId", id))
                .andBeingOneOfTypes(AccountCredited.class.getName(), AccountDebited.class.getName());
    }

    @AppendCriteriaBuilder
    private static EventCriteria resolveAppendCriteria(String id) {
        // Only a concurrent debit, never a credit, can invalidate a decision based on the balance.
        return EventCriteria
                .havingTags(Tag.of("accountId", id))
                .andBeingOneOfTypes(AccountDebited.class.getName());
    }

    @CommandHandler
    public static void create(CreditAccount command, EventAppender appender) {
        appender.append(new AccountCredited(command.accountId(), command.amount()));
    }

    @CommandHandler
    public void handle(CreditAccount command, EventAppender appender) {
        appender.append(new AccountCredited(command.accountId(), command.amount()));
    }

    @CommandHandler
    public void handle(DebitAccount command, EventAppender appender) {
        if (balance < command.amount()) {
            throw new IllegalStateException("Insufficient funds");
        }
        appender.append(new AccountDebited(command.accountId(), command.amount()));
    }

    @EventSourcingHandler
    public void on(AccountCredited event) {
        this.balance += event.amount();
    }

    @EventSourcingHandler
    public void on(AccountDebited event) {
        this.balance -= event.amount();
    }

    public String id() {
        return id;
    }

    public long balance() {
        return balance;
    }
}
