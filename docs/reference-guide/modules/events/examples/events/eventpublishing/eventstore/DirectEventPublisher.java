package events.eventpublishing.eventstore;

// tag::publish-via-event-store[]
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

class DirectEventPublisher {

    private EventStore eventStore;

    public CompletableFuture<Void> publishWithMetadata() {
        EventMessage eventMessage = new GenericEventMessage(
                new MessageType(CardIssuedEvent.class),
                new CardIssuedEvent("cardId", 100, "shopId")
        );
        return eventStore.publish(null, eventMessage);
    }
}
// end::publish-via-event-store[]

record CardIssuedEvent(String cardId, int amount, String shopId) {

}
