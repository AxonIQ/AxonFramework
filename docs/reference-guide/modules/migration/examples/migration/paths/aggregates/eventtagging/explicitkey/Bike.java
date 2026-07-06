package migration.paths.aggregates.eventtagging.explicitkey;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;

// tag::bike-entity[]
// Entity
@EventSourcedEntity(tagKey = "Bike") // <1>
public class Bike {

    private String bikeId;

    // command and event sourcing handlers omitted
}

// end::bike-entity[]
