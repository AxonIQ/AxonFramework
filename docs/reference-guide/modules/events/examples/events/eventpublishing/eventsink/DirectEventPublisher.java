package events.eventpublishing.eventsink;

// tag::publish-via-event-sink[]
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

class DirectEventPublisher {

    private EventSink eventSink;

    public CompletableFuture<Void> publishWithMetadata() {
        EventMessage eventMessage = new GenericEventMessage(
                new MessageType(CardIssuedEvent.class),
                new CardIssuedEvent("cardId", 100, "shopId")
        ).withMetadata(Map.of("userId", "user123", "source", "api"));
        return eventSink.publish(null, eventMessage);
    }
}
// end::publish-via-event-sink[]

record CardIssuedEvent(String cardId, int amount, String shopId) {

}
