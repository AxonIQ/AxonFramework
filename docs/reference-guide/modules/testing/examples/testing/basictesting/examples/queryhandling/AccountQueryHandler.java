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
package testing.basictesting.examples.queryhandling;

import testing.basictesting.fixtures.AccountBalance;
import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.GetBalanceQuery;
import testing.basictesting.fixtures.MoneyDepositedEvent;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Query handling component referenced by, but not shown alongside, the tagged example on the documentation page.
 * Maintains a simple in-memory balance projection, updated through the same event handling registration that feeds
 * the pooled streaming processor configured in the example.
 */
class AccountQueryHandler {

    private final Map<String, Double> balances = new ConcurrentHashMap<>();

    @EventHandler
    void on(AccountCreatedEvent event) {
        balances.put(event.accountId(), event.balance());
    }

    @EventHandler
    void on(MoneyDepositedEvent event) {
        balances.merge(event.accountId(), event.amount(), Double::sum);
    }

    @QueryHandler
    AccountBalance handle(GetBalanceQuery query) {
        return new AccountBalance(balances.getOrDefault(query.accountId(), 0.0));
    }
}
