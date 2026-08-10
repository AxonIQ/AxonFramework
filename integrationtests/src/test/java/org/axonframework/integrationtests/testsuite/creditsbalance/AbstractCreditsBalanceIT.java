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

package org.axonframework.integrationtests.testsuite.creditsbalance;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.integrationtests.testsuite.AbstractIT;
import org.axonframework.integrationtests.testsuite.creditsbalance.commands.TopUpCredits;
import org.axonframework.integrationtests.testsuite.creditsbalance.commands.UseCredits;
import org.axonframework.integrationtests.testsuite.creditsbalance.events.CreditsToppedUp;
import org.axonframework.integrationtests.testsuite.creditsbalance.events.CreditsUsed;
import org.axonframework.integrationtests.testsuite.creditsbalance.state.TopUpCreditsBalance;
import org.axonframework.integrationtests.testsuite.creditsbalance.state.UseCreditsBalance;
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
 * End-to-end test suite proving that splitting a shared decision model into one entity per command expresses
 * per-command asymmetric append criteria on its own, without a handler-facing override: {@link TopUpCreditsBalance}
 * and {@link UseCreditsBalance} both source every top-up and usage to compute the balance, but each declares its
 * own {@code @AppendCriteriaBuilder} that only guards against the concurrent events that could invalidate its own
 * decision - a usage only conflicts with a concurrent usage, a top-up only conflicts with a concurrent top-up.
 * <p>
 * Concurrency is driven deterministically (rather than via real multi-threaded races) by manually controlling
 * {@link UnitOfWork} phases: an entity is sourced in one phase, an independent transaction is fully committed in a
 * later phase (simulating a write that lands between sourcing and appending), and the original append then happens
 * in that same later phase - exactly what the entity's own command handler does, replicated here to inject the
 * concurrent write in between.
 */
public abstract class AbstractCreditsBalanceIT extends AbstractIT {

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
        var topUpCreditsBalance = EventSourcedEntityModule.autodetected(String.class, TopUpCreditsBalance.class);
        var useCreditsBalance = EventSourcedEntityModule.autodetected(String.class, UseCreditsBalance.class);
        return EventSourcingConfigurer.create()
                                      .componentRegistry(cr -> cr.registerModule(topUpCreditsBalance)
                                                                  .registerModule(useCreditsBalance));
    }

    @Test
    void toppingUpAndUsingCreditsUpdatesBalance() {
        String accountId = createId("account");

        topUp(accountId, 1);
        use(accountId, 1);
        topUp(accountId, 1);

        assertThat(balanceOf(accountId)).isEqualTo(1);
    }

    @Test
    void usingMoreThanBalanceIsRejectedByBusinessLogicBeforeAnyAppendCriteriaCheck() {
        String accountId = createId("account");
        topUp(accountId, 1);

        assertThatThrownBy(() -> use(accountId, 2))
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Insufficient credits");

        assertThat(balanceOf(accountId)).isEqualTo(1);
    }

    @Test
    void toppingUpBeyondTheLimitIsRejectedByBusinessLogicBeforeAnyAppendCriteriaCheck() {
        String accountId = createId("account");
        topUp(accountId, 100);

        assertThatThrownBy(() -> topUp(accountId, 1))
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Credits limit exceeded");

        assertThat(balanceOf(accountId)).isEqualTo(100);
    }

    @Test
    void concurrentTopUpDoesNotConflictWithTheNarrowerUseCreditsAppendCriteria() {
        String accountId = createId("account");
        topUp(accountId, 1);

        UnitOfWork uow = unitOfWorkFactory.create();
        uow.runOnPreInvocation(context -> loadUseCreditsBalance(accountId, context))
           .runOnPostInvocation(context -> {
               // An independent transaction commits a top-up for the same account, after this transaction already
               // sourced its own view of the balance, but before it appends its own usage.
               topUp(accountId, 1);
               appendUsage(accountId, 1, context);
           });

        uow.execute().join();

        // Both the concurrent top-up and this transaction's usage landed: no conflict was raised.
        assertThat(balanceOf(accountId)).isEqualTo(1 + 1 - 1);
    }

    @Test
    void concurrentUsageConflictsWithTheNarrowerUseCreditsAppendCriteria() {
        String accountId = createId("account");
        topUp(accountId, 1);

        UnitOfWork uow = unitOfWorkFactory.create();
        uow.runOnPreInvocation(context -> loadUseCreditsBalance(accountId, context))
           .runOnPostInvocation(context -> {
               // An independent transaction commits a USAGE for the same account in that same race window.
               use(accountId, 1);
               appendUsage(accountId, 1, context);
           });

        assertThatThrownBy(() -> uow.execute().join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(AppendEventsTransactionRejectedException.class);
        // The concurrent usage committed, but this transaction's own usage was rejected.
        assertThat(balanceOf(accountId)).isEqualTo(1 - 1);
    }

    @Test
    void concurrentUsageDoesNotConflictWithTheNarrowerTopUpAppendCriteria() {
        String accountId = createId("account");
        topUp(accountId, 1);

        UnitOfWork uow = unitOfWorkFactory.create();
        uow.runOnPreInvocation(context -> loadTopUpCreditsBalance(accountId, context))
           .runOnPostInvocation(context -> {
               // An independent transaction commits a usage for the same account, after this transaction already
               // sourced its own view of the balance, but before it appends its own top-up.
               use(accountId, 1);
               appendTopUp(accountId, 1, context);
           });

        uow.execute().join();

        // Both the concurrent usage and this transaction's top-up landed: no conflict was raised.
        assertThat(balanceOf(accountId)).isEqualTo(1 - 1 + 1);
    }

    @Test
    void concurrentTopUpConflictsWithTheNarrowerTopUpAppendCriteria() {
        String accountId = createId("account");
        topUp(accountId, 1);

        UnitOfWork uow = unitOfWorkFactory.create();
        uow.runOnPreInvocation(context -> loadTopUpCreditsBalance(accountId, context))
           .runOnPostInvocation(context -> {
               // An independent transaction commits a TOP-UP for the same account in that same race window.
               topUp(accountId, 1);
               appendTopUp(accountId, 1, context);
           });

        assertThatThrownBy(() -> uow.execute().join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseInstanceOf(AppendEventsTransactionRejectedException.class);
        // The concurrent top-up committed, but this transaction's own top-up was rejected.
        assertThat(balanceOf(accountId)).isEqualTo(1 + 1);
    }

    private void loadTopUpCreditsBalance(String accountId, ProcessingContext context) {
        context.component(StateManager.class)
               .repository(TopUpCreditsBalance.class, String.class)
               .load(accountId, context)
               .join();
    }

    private void loadUseCreditsBalance(String accountId, ProcessingContext context) {
        context.component(StateManager.class)
               .repository(UseCreditsBalance.class, String.class)
               .load(accountId, context)
               .join();
    }

    private void appendUsage(String accountId, long amount, ProcessingContext context) {
        EventAppender.forContext(context).append(new CreditsUsed(accountId, amount));
    }

    private void appendTopUp(String accountId, long amount, ProcessingContext context) {
        EventAppender.forContext(context).append(new CreditsToppedUp(accountId, amount));
    }

    private void topUp(String accountId, long amount) {
        commandGateway.send(new TopUpCredits(accountId, amount)).getResultMessage().join();
    }

    private void use(String accountId, long amount) {
        commandGateway.send(new UseCredits(accountId, amount)).getResultMessage().join();
    }

    private long balanceOf(String accountId) {
        UnitOfWork uow = unitOfWorkFactory.create();
        return uow.executeWithResult(context -> context.component(StateManager.class)
                                                        .repository(UseCreditsBalance.class, String.class)
                                                        .load(accountId, context)
                                                        .thenApply(managed -> managed.entity().balance()))
                  .join();
    }
}
