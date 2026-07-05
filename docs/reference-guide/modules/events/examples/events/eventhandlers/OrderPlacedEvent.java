package events.eventhandlers;

import org.axonframework.messaging.eventhandling.annotation.Event;

// tag::event-annotated-order-placed[]
@Event(
    namespace = "orders",               // <1>
    name = "OrderPlaced",               // Local name; defaults to simple class name
    version = "1.0.0"                   // Defaults to "0.0.1"
)
public record OrderPlacedEvent(
    String orderId,
    String customerId
) {
}
// end::event-annotated-order-placed[]
