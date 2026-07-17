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
package messagingconcepts.processingcontext;

import java.util.concurrent.CompletableFuture;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.commandhandling.gateway.CommandResult;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CommandDispatcherPropagation {

    private static final Logger logger = LoggerFactory.getLogger(CommandDispatcherPropagation.class);

    // tag::command-dispatcher[]
    @EventHandler
    public CompletableFuture<Void> on(OrderPlacedEvent event,
                                      CommandDispatcher commandDispatcher) {
        // CommandDispatcher is already bound to the current ProcessingContext
        // Correlation data propagates automatically
        CommandResult result = commandDispatcher.send(
            new ProcessOrderCommand(event.getOrderId())
        );

        // Return the CompletableFuture so the handler only completes when the command finishes
        return result.getResultMessage()
                     .thenAccept(r -> logger.info("Command processed successfully"))
                     .exceptionally(ex -> {
                         logger.error("Command failed: {}", ex.getMessage());
                         return null;
                     });
    }
    // end::command-dispatcher[]
}
