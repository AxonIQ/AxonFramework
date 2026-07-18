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
package migration.paths.aggregates.index.creationpolicycreateifmissing;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;

@EventSourcedEntity
public class GiftCard {

    @EntityCreator
    protected GiftCard() {
    }

    // tag::creation-policy-create-if-missing[]
    @CommandHandler
    public static void handle(IssueGiftCard cmd,
                              EventAppender eventAppender,
                              @InjectEntity @Nullable GiftCard giftCard) {
        if (giftCard != null) {
            throw new IllegalStateException("GiftCard already exists");
        }
        eventAppender.append(new GiftCardIssued(cmd.cardId(), cmd.amount()));
    }
    // end::creation-policy-create-if-missing[]
}
