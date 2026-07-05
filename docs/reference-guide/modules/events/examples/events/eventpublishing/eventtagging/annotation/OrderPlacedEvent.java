package events.eventpublishing.eventtagging.annotation;

// tag::event-tag-annotation[]
import org.axonframework.eventsourcing.annotation.EventTag;

record OrderPlacedEvent(
    @EventTag String orderId,
    @EventTag(key = "region") String orderRegion,
    int amount
) {
}
// end::event-tag-annotation[]
