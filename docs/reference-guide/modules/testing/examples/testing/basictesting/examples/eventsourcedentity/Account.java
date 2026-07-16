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
package testing.basictesting.examples.eventsourcedentity;

import testing.basictesting.fixtures.AccountClosedEvent;
import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.CloseAccountCommand;
import testing.basictesting.fixtures.CreateAccountCommand;
import testing.basictesting.fixtures.DepositMoneyCommand;
import testing.basictesting.fixtures.InsufficientBalanceException;
import testing.basictesting.fixtures.MoneyDepositedEvent;
import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

/**
 * Event-sourced entity referenced by, but not shown alongside, the tagged example on the documentation page: here
 * the command handling is combined with the entity in one object, instead of being registered as a separate
 * stateful command handling component.
 */
@EventSourcedEntity(tagKey = "accountId")
public class Account {

    private String accountId;
    private double balance;
    private boolean closed;

    @CommandHandler
    public static String handle(CreateAccountCommand command, EventAppender eventAppender) {
        eventAppender.append(new AccountCreatedEvent(command.accountId(), command.initialBalance()));
        return command.accountId();
    }

    @CommandHandler
    public void handle(DepositMoneyCommand command, EventAppender eventAppender) {
        eventAppender.append(new MoneyDepositedEvent(command.accountId(), command.amount()));
    }

    @CommandHandler
    public void handle(WithdrawMoneyCommand command, EventAppender eventAppender) {
        if (command.amount() > balance) {
            throw new InsufficientBalanceException(command.accountId(), command.amount());
        }
        eventAppender.append(new MoneyWithdrawnEvent(command.accountId(), command.amount()));
    }

    @CommandHandler
    public void handle(CloseAccountCommand command, EventAppender eventAppender) {
        eventAppender.append(new AccountClosedEvent(command.accountId()));
    }

    @EventSourcingHandler
    private void on(AccountCreatedEvent event) {
        this.accountId = event.accountId();
        this.balance = event.balance();
    }

    @EventSourcingHandler
    private void on(MoneyDepositedEvent event) {
        this.balance += event.amount();
    }

    @EventSourcingHandler
    private void on(MoneyWithdrawnEvent event) {
        this.balance -= event.amount();
    }

    @EventSourcingHandler
    private void on(AccountClosedEvent event) {
        this.closed = true;
    }

    @EntityCreator
    protected Account() {
    }
}
