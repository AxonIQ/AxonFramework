package events.eventstoreinternals.tags.annotation;

// tag::event-tag-annotation[]
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.messaging.eventhandling.annotation.Event;

@Event(name = "orderPlaced")
record OrderPlacedEvent(
    @EventTag OrderId customerId,
    @EventTag(key = "region") String orderRegion,
    String orderId
) {
}
// end::event-tag-annotation[]

record OrderId(String value) {

}
