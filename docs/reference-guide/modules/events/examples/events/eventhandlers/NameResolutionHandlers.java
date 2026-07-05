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
