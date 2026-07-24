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
package events.eventversioning;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class OrderVersioningHandlers {

    // tag::enriched-order-handler[]
    @EventHandler
    public void on(EnrichedOrderPlacedEvent event) {
        // Axon converts the stored OrderPlacedEvent to EnrichedOrderPlacedEvent
        // The productCount field is computed during conversion
        notifyAnalytics(event.productCount());
    }
    // end::enriched-order-handler[]

    // tag::legacy-order-handler[]
    @EventHandler(eventName = "OrderPlaced")
    public void on(OrderPlacedEvent event) {
        // Old handling flow...
    }
    // end::legacy-order-handler[]

    private void notifyAnalytics(int productCount) {
        // Forward the computed product count to the analytics service
    }
}
