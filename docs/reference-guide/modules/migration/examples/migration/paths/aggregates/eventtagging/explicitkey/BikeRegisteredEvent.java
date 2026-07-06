package migration.paths.aggregates.eventtagging.explicitkey;

import org.axonframework.eventsourcing.annotation.EventTag;

// tag::bike-registered-event[]
// Event
public record BikeRegisteredEvent(
        @EventTag(key = "Bike") String bikeId, // <2>
        String bikeType,
        String location
) { }
// end::bike-registered-event[]
