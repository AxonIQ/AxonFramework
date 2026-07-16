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
package testing.basictesting.examples.commandhandling;

import testing.basictesting.fixtures.Account;
import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.CreateAccountCommand;
import testing.basictesting.fixtures.InsufficientBalanceException;
import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.WithdrawMoneyCommand;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

/**
 * Stateful command handling component referenced by, but not shown alongside, the tagged example on the
 * documentation page: the {@link Account} state is registered separately with the configurer, and injected here.
 */
class AccountCommandHandler {

    @CommandHandler
    String handle(CreateAccountCommand command, EventAppender eventAppender) {
        eventAppender.append(new AccountCreatedEvent(command.accountId(), command.initialBalance()));
        return command.accountId();
    }

    @CommandHandler
    void handle(WithdrawMoneyCommand command, @InjectEntity Account account, EventAppender eventAppender) {
        if (command.amount() > account.balance()) {
            throw new InsufficientBalanceException(command.accountId(), command.amount());
        }
        eventAppender.append(new MoneyWithdrawnEvent(command.accountId(), command.amount()));
    }
}
