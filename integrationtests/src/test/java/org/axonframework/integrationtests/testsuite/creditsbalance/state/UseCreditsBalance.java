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

package org.axonframework.integrationtests.testsuite.creditsbalance.state;

import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.SourcingCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.integrationtests.testsuite.creditsbalance.commands.UseCredits;
import org.axonframework.integrationtests.testsuite.creditsbalance.events.CreditsToppedUp;
import org.axonframework.integrationtests.testsuite.creditsbalance.events.CreditsUsed;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;

/**
 * The decision model for handling {@link UseCredits}, one entity per command.
 * <p>
 * Sourcing needs every top-up and usage to compute the current balance, but only a concurrent usage, never a
 * top-up, can invalidate a decision that assumed a certain balance: a top-up can only raise the balance, so it can
 * never turn a valid usage into an invalid one.
 * <p>
 * This entity never creates a new account: using credits on an account that was never topped up fails, so there is
 * no creational command handler here, unlike {@link TopUpCreditsBalance}.
 */
@EventSourcedEntity
public class UseCreditsBalance {

    private long balance;

    @EntityCreator
    public UseCreditsBalance(@InjectEntityId String accountId) {

    }

    @SourcingCriteriaBuilder
    private static EventCriteria resolveSourcingCriteria(String accountId) {
        return EventCriteria
                .havingTags(Tag.of("accountId", accountId))
                .andBeingOneOfTypes(CreditsToppedUp.class.getName(), CreditsUsed.class.getName());
    }

    @AppendCriteriaBuilder
    private static EventCriteria resolveAppendCriteria(String accountId) {
        // Only a concurrent usage, never a top-up, can invalidate a decision based on the balance.
        return EventCriteria
                .havingTags(Tag.of("accountId", accountId))
                .andBeingOneOfTypes(CreditsUsed.class.getName());
    }

    @CommandHandler
    public void handle(UseCredits command, EventAppender appender) {
        if (balance < command.amount()) {
            throw new IllegalStateException("Insufficient credits");
        }
        appender.append(new CreditsUsed(command.accountId(), command.amount()));
    }

    @EventSourcingHandler
    public void on(CreditsToppedUp event) {
        this.balance += event.amount();
    }

    @EventSourcingHandler
    public void on(CreditsUsed event) {
        this.balance -= event.amount();
    }

    public long balance() {
        return balance;
    }
}
