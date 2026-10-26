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

import org.axonframework.messaging.core.annotation.QualifiedName;
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
    @EventHandler(event = @QualifiedName(namespace = "orders", name = "OrderPlaced"))
    public void handleOrderPlaced(Map<String, Object> eventData) {
        // Handles events with qualified name "orders.OrderPlaced"
        // Payload is converted to Map at handling time
    }
    // end::event-handler-explicit-name[]

    // tag::event-handler-multiple-names[]
    @EventHandler(event = {
            @QualifiedName(namespace = "orders", name = "OrderPlaced"),
            @QualifiedName(namespace = "legacy.orders", name = "OrderSubmitted")
    })
    public void onOrderPlacedOrSubmitted(OrderPlacedEvent event) {
        // Handles events named "orders.OrderPlaced" and "legacy.orders.OrderSubmitted"
    }
    // end::event-handler-multiple-names[]

    // tag::event-handler-qualified-name-defaults[]
    @EventHandler(event = @QualifiedName(namespace = "legacy.orders"))
    public void onLegacyOrderPlaced(OrderPlacedEvent event) {
        // Handles events named "legacy.orders.OrderPlaced":
        // the local name defaults to that of the OrderPlacedEvent ("OrderPlaced")
    }
    // end::event-handler-qualified-name-defaults[]
}
