package events.eventpublishing.eventbus;

// tag::publish-via-event-bus[]
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

class DirectEventPublisher {

    private EventBus eventBus;

    public CompletableFuture<Void> publishWithMetadata() {
        EventMessage eventMessage = new GenericEventMessage(
                new MessageType(CardIssuedEvent.class),
                new CardIssuedEvent("cardId", 100, "shopId")
        );
        return eventBus.publish(null, eventMessage);
    }
}
// end::publish-via-event-bus[]

record CardIssuedEvent(String cardId, int amount, String shopId) {

}
