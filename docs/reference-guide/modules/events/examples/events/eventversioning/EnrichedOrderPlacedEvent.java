package events.eventversioning;

import org.axonframework.messaging.eventhandling.annotation.Event;

import java.util.List;

// tag::enriched-order-placed-event[]
@Event(name = "OrderPlaced", version = "2.0.0")
public record EnrichedOrderPlacedEvent(
    String orderId,
    String customerId,
    List<String> productIds,
    int productCount  // Computed from productIds
) {
    // Compact constructor that computes productCount
    public EnrichedOrderPlacedEvent(String orderId, String customerId, List<String> productIds) {
        this(orderId, customerId, productIds, productIds != null ? productIds.size() : 0);
    }
}
// end::enriched-order-placed-event[]
