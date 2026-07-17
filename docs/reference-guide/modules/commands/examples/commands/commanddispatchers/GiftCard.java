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
package commands.commanddispatchers;

import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

// tag::injecting-command-dispatcher[]
@EventSourced
public class GiftCard {

    @CommandHandler
    public void handle(RedeemCardCommand command, EventAppender eventAppender, CommandDispatcher dispatcher) { // <1>
        // Validate and apply event
        eventAppender.append(new CardRedeemedEvent(command.cardId(), command.amount()));

        // Dispatch another command using the dispatcher
        dispatcher.send(new SendThankYouEmailCommand(command.cardId())); // <2>
    }
}
// end::injecting-command-dispatcher[]
