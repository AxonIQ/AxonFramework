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
package messagingconcepts.supportedparameters;

import java.util.concurrent.CompletableFuture;

import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.commandhandling.gateway.CommandResult;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EventHandlerCommandDispatcherExample {

    private static final Logger logger = LoggerFactory.getLogger(EventHandlerCommandDispatcherExample.class);

    // tag::event-handler-command-dispatcher[]
    @EventHandler
    public CompletableFuture<Void> handle(OrderPlacedEvent event,
                                          CommandDispatcher dispatcher) {
        // Update the state based on the event
        updateState(event);

        // Dispatch follow-up command
        CommandResult result = dispatcher.send(new ShipOrderCommand(event.orderId()));

        // Return the CompletableFuture so the handler only completes when the command finishes
        return result.getResultMessage()
                     .thenAccept(r -> logger.info("Shipping initiated"))
                     .exceptionally(ex -> {
                         logger.error("Failed to initiate shipping: {}", ex.getMessage());
                         // Handle error appropriately
                         return null;
                     });
    }
    // end::event-handler-command-dispatcher[]

    private void updateState(OrderPlacedEvent event) {
    }
}
