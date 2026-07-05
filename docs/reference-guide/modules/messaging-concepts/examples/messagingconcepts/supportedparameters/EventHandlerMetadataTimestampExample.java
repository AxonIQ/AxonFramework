package messagingconcepts.supportedparameters;

import java.time.Instant;

import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.annotation.Timestamp;

class EventHandlerMetadataTimestampExample {

    // tag::event-handler-metadata-timestamp[]
    @EventHandler
    public void on(OrderPlacedEvent event,
                   @MetadataValue("userId") String userId,
                   @Timestamp Instant timestamp) {
        // userId is extracted from metadata
        // timestamp contains when the event was created
        updateProjection(event, userId, timestamp);
    }
    // end::event-handler-metadata-timestamp[]

    private void updateProjection(OrderPlacedEvent event, String userId, Instant timestamp) {
    }
}
