package events.eventpublishing.eventtagging.custom;

// tag::custom-tag-resolver[]
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;

import java.util.Set;

public class CustomTagResolver implements TagResolver {

    @Override
    public Set<Tag> resolve(EventMessage event) {
        Object payload = event.payload();
        if (payload instanceof OrderPlacedEvent orderEvent) {
            return Set.of(
                    Tag.of("orderId", orderEvent.orderId()),
                    Tag.of("customer", orderEvent.customerId())
            );
        }
        return Set.of();
    }
}
// end::custom-tag-resolver[]

record OrderPlacedEvent(String orderId, String orderRegion, String customerId, int amount) {

}
