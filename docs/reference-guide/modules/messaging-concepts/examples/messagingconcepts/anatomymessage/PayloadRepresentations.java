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
package messagingconcepts.anatomymessage;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class PayloadRepresentations {

    // tag::payload-representations[]
    // Handler 1: Receives as domain object (OrderPlacedEvent is @Event annotated)
    @EventHandler
    public void handle(OrderPlacedEvent event) {
        // Payload is converted to OrderPlacedEvent
    }

    // Handler 2: Receives as JSON (must specify messageType)
    @EventHandler(eventName = "com.example.orders.OrderPlaced")
    public void handle(JsonNode event) {
        // Same message, converted to JsonNode
    }

    // Handler 3: Receives as Map (must specify messageType)
    @EventHandler(eventName = "com.example.orders.OrderPlaced")
    public void handle(Map<String, Object> event) {
        // Same message, converted to Map
    }
    // end::payload-representations[]
}
