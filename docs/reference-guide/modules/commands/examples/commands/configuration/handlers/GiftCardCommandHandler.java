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
package commands.configuration.handlers;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.repository.Repository;

// tag::spring-command-handling-component[]
import org.springframework.stereotype.Component;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

@Component
public class GiftCardCommandHandler {

    private final Repository<String, GiftCard> giftCardRepository;

    public GiftCardCommandHandler(Repository<String, GiftCard> giftCardRepository) {
        this.giftCardRepository = giftCardRepository;
    }

    @CommandHandler
    public void handle(CancelCardCommand cmd,
                      ProcessingContext context,
                      EventAppender eventAppender) {
        giftCardRepository.load(cmd.cardId(), context)
            .thenAccept(managedCard -> {
                GiftCard card = managedCard.entity();
                if (card.canBeCancelled()) {
                    eventAppender.append(new CardCancelledEvent(cmd.cardId()));
                }
            });
    }
}
// end::spring-command-handling-component[]
