package messagingconcepts.anatomymessage;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class PayloadRepresentations {

    // tag::payload-representations[]
    // Handler 1: Receives as domain object (OrderPlacedEvent is @Event annotated)
    @EventHandler
    public void handle(OrderPlacedEvent event) {
        // Payload is converted to OrderPlacedEvent
    }

    // Handler 2: Receives as JSON (must specify messageType)
    @EventHandler(eventName = "com.example.orders.OrderPlaced")
    public void handle(JsonNode event) {
        // Same message, converted to JsonNode
    }

    // Handler 3: Receives as Map (must specify messageType)
    @EventHandler(eventName = "com.example.orders.OrderPlaced")
    public void handle(Map<String, Object> event) {
        // Same message, converted to Map
    }
    // end::payload-representations[]
}
