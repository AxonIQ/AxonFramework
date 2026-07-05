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
