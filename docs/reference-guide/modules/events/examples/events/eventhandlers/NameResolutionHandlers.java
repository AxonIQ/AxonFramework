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
package events.eventhandlers;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;

import java.util.Map;

class NameResolutionHandlers {

    // tag::event-handler-implicit-name[]
    @EventHandler
    public void on(OrderPlacedEvent event) {
        // ...
    }
    // end::event-handler-implicit-name[]

    // tag::event-handler-explicit-name[]
    @EventHandler(eventName = "orders.OrderPlaced")
    public void handleOrderPlaced(Map<String, Object> eventData) {
        // Handles events with qualified name "orders.OrderPlaced"
        // Payload is converted to Map at handling time
    }
    // end::event-handler-explicit-name[]
}
