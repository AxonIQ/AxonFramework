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
package messagingconcepts.componentmessageintercepting.entityannotated;

// tag::entity-command-interceptor[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

@EventSourcedEntity(tagKey = "cardId")
public class GiftCard {

    private String cardId;
    private boolean redeemed;

    @EntityCreator
    public GiftCard() {
    }

    @CommandHandlerInterceptor
    void rejectIfAlreadyRedeemed(CommandMessage command) {
        if (redeemed) {
            throw new IllegalStateException("Gift card already redeemed");
        }
    }

    @CommandHandler
    void handle(RedeemGiftCard command, EventAppender eventAppender) {
        eventAppender.append(new GiftCardRedeemed(command.cardId()));
    }

    @EventSourcingHandler
    void on(GiftCardRedeemed event) {
        this.cardId = event.cardId();
        this.redeemed = true;
    }
}
// end::entity-command-interceptor[]
