package events.eventversioning;

import org.axonframework.messaging.eventhandling.annotation.Event;

import java.util.List;

// tag::order-placed-event[]
@Event(name = "OrderPlaced", version = "1.0.0")
public record OrderPlacedEvent(
    String orderId,
    String customerId,
    List<String> productIds
) {
}
// end::order-placed-event[]
