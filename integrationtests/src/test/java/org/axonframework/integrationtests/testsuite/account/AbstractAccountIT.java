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

package org.axonframework.integrationtests.testsuite.account;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.integrationtests.testsuite.AbstractIT;
import org.axonframework.integrationtests.testsuite.account.commands.CreditAccount;
import org.axonframework.integrationtests.testsuite.account.commands.DebitAccount;
import org.axonframework.integrationtests.testsuite.account.events.AccountDebited;
import org.axonframework.integrationtests.testsuite.account.state.Account;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.StateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end test suite proving that an {@code @EventSourcedEntity} configured with independent
 * {@code @SourcingCriteriaBuilder} and {@code @AppendCriteriaBuilder} methods behaves correctly through the full
 * configuration, command-dispatch, and storage stack: sourcing folds every credit and debit into the balance, while
 * the narrower append criteria only guards against a concurrent debit.
 * <p>
 * Concurrency is driven deterministically (rather than via real multi-threaded races) by manually controlling
 * {@link UnitOfWork} phases: an entity is sourced in one phase, an independent transaction is fully committed in a
 * later phase (simulating a write that lands between sourcing and appending), and the original append then happens
 * in that same later phase.
 */
public abstract class AbstractAccountIT extends AbstractIT {

    protected UnitOfWorkFactory unitOfWorkFactory;

    @BeforeEach
    void doStartApp() {
        startApp();
    }

    @Override
    protected void startApp() {
        super.startApp();
        unitOfWorkFactory = startedConfiguration.getComponent(UnitOfWorkFactory.class);
    }

    @Override
    protected ApplicationConfigurer applicationConfigurer() {
        var accountEntity = EventSourcedEntityModule.autodetected(String.class, Account.class);
        return EventSourcingConfigurer.create()
                                      .componentRegistry(cr -> cr.registerModule(accountEntity));
    }

    @Test
    void creditingAndDebitingUpdatesBalance() {
        String accountId = createId("account");

        credit(accountId, 100);
        credit(accountId, 50);
        debit(accountId, 30);

        assertThat(balanceOf(accountId)).isEqualTo(120);
    }

    @Test
    void debitingMoreThanBalanceIsRejectedByBusinessLogic() {
        String accountId = createId("account");
        credit(accountId, 50);

        assertThatThrownBy(() -> debit(accountId, 100))
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Insufficient funds");

        assertThat(balanceOf(accountId)).isEqualTo(50);
    }

    @Test
    void concurrentCreditDoesNotConflictWithTheNarrowerAppendCriteria() {
        String accountId = createId("account");
        credit(accountId, 1_000);

        UnitOfWork uow = unitOfWorkFactory.create();
        uow.runOnPreInvocation(context -> loadAccount(accountId, context))
           .runOnPostInvocation(context -> {
               // An independent transaction commits a credit for the same account, after this transaction
               // already sourced its own view of the balance, but before it appends its own debit.
               credit(accountId, 1);
               appendDebit(accountId, 100, context);
           });

        uow.execute().join();

        // Both the concurrent credit and this transaction's debit landed: no conflict was raised.
        assertThat(balanceOf(accountId)).isEqualTo(1_000 + 1 - 100);
    }

    @Test
    void concurrentDebitConflictsWithTheNarrowerAppendCriteria() {
        String accountId = createId("account");
        credit(accountId, 1_000);

        UnitOfWork uow = unitOfWorkFactory.create();
        uow.runOnPreInvocation(context -> loadAccount(accountId, context))
           .runOnPostInvocation(context -> {
               // An independent transaction commits a DEBIT for the same account in that same race window.
               debit(accountId, 1);
               appendDebit(accountId, 100, context);
           });

        assertThatThrownBy(() -> uow.execute().join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(AppendEventsTransactionRejectedException.class);
        // The concurrent debit committed, but this transaction's own debit was rejected.
        assertThat(balanceOf(accountId)).isEqualTo(1_000 - 1);
    }

    private void loadAccount(String accountId, ProcessingContext context) {
        context.component(StateManager.class)
               .repository(Account.class, String.class)
               .load(accountId, context)
               .join();
    }

    private void appendDebit(String accountId, long amount, ProcessingContext context) {
        EventAppender.forContext(context).append(new AccountDebited(accountId, amount));
    }

    private void credit(String accountId, long amount) {
        commandGateway.send(new CreditAccount(accountId, amount)).getResultMessage().join();
    }

    private void debit(String accountId, long amount) {
        commandGateway.send(new DebitAccount(accountId, amount)).getResultMessage().join();
    }

    private long balanceOf(String accountId) {
        UnitOfWork uow = unitOfWorkFactory.create();
        return uow.executeWithResult(context -> context.component(StateManager.class)
                                                        .repository(Account.class, String.class)
                                                        .load(accountId, context)
                                                        .thenApply(managed -> managed.entity().balance()))
                  .join();
    }
}
