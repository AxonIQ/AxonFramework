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

import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.commandhandling.gateway.CommandResult;
import org.axonframework.messaging.core.Metadata;

import java.util.concurrent.CompletableFuture;

class DispatcherApiExample {

    private CommandDispatcher dispatcher;

    void sendCommand(Object command) {
        // tag::dispatcher-send[]
        // Send command and get CommandResult
        CommandResult result = dispatcher.send(command);

        // end::dispatcher-send[]
    }

    void sendWithMetadata(Object command, Metadata metadata) {
        // tag::dispatcher-send-metadata[]
        // Send command with metadata
        CommandResult result = dispatcher.send(command, metadata);

        // end::dispatcher-send-metadata[]
    }

    void sendAndGetFuture(Object command) {
        // tag::dispatcher-send-future[]
        // Send command and get CompletableFuture with expected type
        CompletableFuture<String> future = dispatcher.send(command, String.class);

        // end::dispatcher-send-future[]
    }

    void attachHandlers(Object command) {
        // tag::dispatcher-send-handlers[]
        // Attach handlers to the result
        dispatcher.send(command)
                 .onSuccess(String.class, cardNumber -> {
                     // Handle success
                 })
                 .onError(exception -> {
                     // Handle error
                 });
        // end::dispatcher-send-handlers[]
    }
}
