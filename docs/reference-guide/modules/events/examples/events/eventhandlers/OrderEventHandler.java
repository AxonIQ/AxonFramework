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
