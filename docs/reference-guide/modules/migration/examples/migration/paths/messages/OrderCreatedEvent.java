package migration.paths.messages;

import org.axonframework.eventsourcing.annotation.EventTag;

// tag::event-tag-aggregate-identification[]
public class OrderCreatedEvent {
    @EventTag(key = "Order") // "Order" is the aggregate type
    private String orderId;
    // ...
}
// end::event-tag-aggregate-identification[]
