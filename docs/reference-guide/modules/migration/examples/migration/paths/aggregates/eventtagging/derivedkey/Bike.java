package migration.paths.aggregates.eventtagging.derivedkey;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;

// tag::bike-entity-derived-key[]
@EventSourcedEntity(tagKey = "bikeId")
public class Bike {
    private String bikeId;
    // ...
}

// end::bike-entity-derived-key[]
