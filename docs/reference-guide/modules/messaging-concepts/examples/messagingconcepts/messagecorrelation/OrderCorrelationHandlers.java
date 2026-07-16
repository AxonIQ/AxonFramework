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
package messagingconcepts.messagecorrelation;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

class OrderCorrelationHandlers {

    // tag::message-origin-handlers[]
    // When handling a command that publishes an event:
    @CommandHandler
    public void handle(PlaceOrderCommand command, EventAppender appender) {
        // Command message id: "cmd-123"
        // Command has no correlationId yet (it's the root)

        // Create and publish event...
        appender.append(new OrderPlacedEvent());

        // Event metadata will contain:
        // - correlationId: "cmd-123" (from command id, since command had none)
        // - causationId: "cmd-123" (direct parent)
    }

    // When the event triggers another command:
    @EventHandler
    public void on(OrderPlacedEvent event, CommandDispatcher dispatcher) {
        // Event message id: "evt-456"
        // Event metadata:
        // - correlationId: "cmd-123"
        // - causationId: "cmd-123"

        // Create and send command...
        dispatcher.send(new ShipOrderCommand());

        // Command metadata will contain:
        // - correlationId: "cmd-123" (propagated from event)
        // - causationId: "evt-456" (immediate parent)
    }
    // end::message-origin-handlers[]
}

record PlaceOrderCommand() {
}

record OrderPlacedEvent() {
}

record ShipOrderCommand() {
}
