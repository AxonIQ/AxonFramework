package events.eventstoreinternals.criteria;

// The import is indented to the depth of the nested method below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::event-criteria-import[]
    import org.axonframework.messaging.eventstreaming.EventCriteria;
    import org.axonframework.messaging.eventstreaming.Tag;

// end::event-criteria-import[]

class EventCriteriaExamples {

    // tag::event-criteria[]
    public EventCriteria createCriteriaFor(OrderPlacedEvent event) {
        return EventCriteria.havingTags(Tag.of("orderId", event.orderId().toString()))
                            .andBeingOneOfTypes("OrderPlaced");
    }
    // end::event-criteria[]
}

record OrderPlacedEvent(String orderId) {

}
