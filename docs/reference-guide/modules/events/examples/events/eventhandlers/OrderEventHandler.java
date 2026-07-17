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

import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

// tag::order-event-handler[]
public class OrderEventHandler {

    @EventHandler
    public void on(OrderPlacedEvent event) {
        // Update read model
    }

    @EventHandler
    public void logOrderPlaced(OrderPlacedEvent event, @MetadataValue("userId") String userId) {
        // Log the event - this handler is ALSO invoked for OrderPlacedEvent
    }

    @EventHandler
    public void on(OrderShippedEvent event) {
        // Handle order shipped
    }
}
// end::order-event-handler[]

record OrderShippedEvent(String orderId) {

}
