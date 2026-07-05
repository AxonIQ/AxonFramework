package messagingconcepts.anatomymessage;

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;

class CreatingMessages {

    // tag::creating-messages[]
    public EventMessage constructEvent(String orderId,
                                       double amount) {
        // Create an event message
        MessageType orderPlacedType = new MessageType(
                new QualifiedName("com.example.orders", "OrderPlaced"),
                "1.0"
        );
        return new GenericEventMessage(
                orderPlacedType,
                new OrderPlacedEvent(orderId, amount),
                Metadata.with("userId", "user-123")
        );
    }
    // end::creating-messages[]
}
