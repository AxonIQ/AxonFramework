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
package events.eventstoreinternals.appendcriteria.annotation;

// tag::annotated-append-criteria[]
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.annotation.InjectEntity;

public class CreditCommandHandler {

    @CommandHandler
    void handle(UseCredits command,
                @InjectEntity(idProperty = "accountId") CreditBalance balance,
                EventAppender eventAppender) {
        if (balance.amount() < command.amount()) {
            throw new IllegalStateException("Insufficient credits");
        }
        eventAppender.append(new CreditsUsed(command.accountId(), command.amount()));
    }

    @CommandHandler
    void handle(TopUpCredits command,
                @InjectEntity(idProperty = "accountId") CreditBalance balance,
                EventAppender eventAppender) {
        eventAppender.append(new CreditsToppedUp(command.accountId(), command.amount()));
    }

    // a concurrent top-up only raises the balance, so only a concurrent CreditsUsed can invalidate this decision
    @AppendCriteriaBuilder
    static EventCriteria appendCriteria(UseCredits command, EventCriteria sourcingCriteria) {
        return sourcingCriteria.replaceEventTypes(new QualifiedName(CreditsUsed.class));
    }

    // every command the class handles needs a builder, so this one keeps the sourcing criteria explicitly
    @AppendCriteriaBuilder
    static EventCriteria appendCriteria(TopUpCredits command, EventCriteria sourcingCriteria) {
        return sourcingCriteria;
    }
}
// end::annotated-append-criteria[]

record UseCredits(String accountId, int amount) {
}

record TopUpCredits(String accountId, int amount) {
}

record CreditsUsed(@EventTag(key = "accountId") String accountId, int amount) {
}

record CreditsToppedUp(@EventTag(key = "accountId") String accountId, int amount) {
}

class CreditBalance {

    int amount() {
        return 0;
    }
}
