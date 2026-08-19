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

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.CommandResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class AsyncSendExample {

    // tag::async-send[]
    private CommandGateway commandGateway; // <1>

    public void sendCommand() {
        String cardId = UUID.randomUUID().toString();

        // <2>
        CommandResult result = commandGateway.send(new IssueCardCommand(cardId, 100, "shopId"));

        // <3>
        result.onSuccess(String.class, cardNumber -> {
            System.out.println("Card issued with number: " + cardNumber);
        }).onError(exception -> {
            System.err.println("Command failed: " + exception.getMessage());
        });

        // <4>
        CompletableFuture<String> futureResult = result.resultAs(String.class);
    }
    // omitted class and constructor
    // end::async-send[]
}
