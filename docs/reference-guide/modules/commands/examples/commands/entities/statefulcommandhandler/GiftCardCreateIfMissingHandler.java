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
package commands.entities.statefulcommandhandler;

import commands.entities.statefulcommandhandler.GiftCardCommands.IssueCardWithInitialRedemption;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.repository.ManagedEntity;

public class GiftCardCreateIfMissingHandler {

    // tag::managed-entity-create-if-missing-handler[]
    @CommandHandler
    public void handle(IssueCardWithInitialRedemption command,
                       @InjectEntity ManagedEntity<String, GiftCard> card,
                       EventAppender appender) {
        if (card.entity() != null) {
            throw new IllegalStateException("GiftCard for id [" + command.cardId() + "] already exists");
        }
        appender.append(new CardIssued(command.cardId(), command.amount()));

        // card.entity() now reflects the CardIssued event appended above.
        GiftCard issued = card.entity();
        if (issued.amount() - command.initialRedemption() < 0) {
            throw new IllegalStateException("Insufficient funds");
        }
        appender.append(new CardRedeemed(command.cardId(), command.initialRedemption()));
    }
    // end::managed-entity-create-if-missing-handler[]
}
