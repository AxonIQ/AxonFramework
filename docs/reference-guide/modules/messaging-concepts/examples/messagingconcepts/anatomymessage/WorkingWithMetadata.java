package messagingconcepts.anatomymessage;

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;

class WorkingWithMetadata {

    void createMetadata() {
        // tag::creating-metadata[]
        Metadata metadata = Metadata.with("userId", "user-123") // <1>
                                    .and("traceId", "trace-456"); // <2>
        // end::creating-metadata[]
    }

    void addMetadata(MessageType messageType, String orderId, double amount) {
        // tag::adding-metadata[]
        EventMessage event = new GenericEventMessage(
            messageType,
            new OrderPlacedEvent(orderId, amount)
        );

        // Add metadata - creates a new message instance
        EventMessage eventWithMetadata = event.withMetadata(
            Metadata.with("userId", "user-123")
        );

        // Add to existing metadata - merges with any existing entries
        EventMessage mergedMessage = eventWithMetadata.andMetadata(
            Metadata.with("correlationId", "corr-789")
        );
        // end::adding-metadata[]
    }

    void metadataStringValues() {
        // tag::metadata-string-values[]
        // Convert numbers, booleans, etc. to String
        Metadata metadata = Metadata.with("count", String.valueOf(42))
                                    .and("enabled", String.valueOf(true));
        // end::metadata-string-values[]
    }
}
