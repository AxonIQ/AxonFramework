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

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.MessageType;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class CommandBusDispatchExample {

    // tag::dispatching-with-commandbus[]
    private CommandBus commandBus; // <1>

    public void dispatchCommands() {
        // <2>
        MessageType commandType = new MessageType(IssueCardCommand.class);
        IssueCardCommand payload = new IssueCardCommand(UUID.randomUUID().toString(), 100, "shopId");
        CommandMessage commandMessage = new GenericCommandMessage(commandType, payload);

        // <3>
        CompletableFuture<CommandResultMessage> resultFuture = commandBus.dispatch(
                commandMessage,
                null // <4>
        );

        // <5>
        resultFuture.whenComplete((resultMsg, exception) -> {
            if (exception != null) {
                // Handle command execution failure
            } else {
                Object commandResult = resultMsg.payload();
                // Handle successful result
            }
        });
    }
    // omitted class and constructor
    // end::dispatching-with-commandbus[]
}
