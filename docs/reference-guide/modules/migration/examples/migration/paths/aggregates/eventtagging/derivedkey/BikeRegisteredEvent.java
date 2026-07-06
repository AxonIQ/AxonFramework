package migration.paths.aggregates.eventtagging.derivedkey;

import org.axonframework.eventsourcing.annotation.EventTag;

// tag::bike-registered-event-derived-key[]
public record BikeRegisteredEvent(
        @EventTag String bikeId, // tag key derived from field name -> "bikeId"
        String bikeType,
        String location
) { }
// end::bike-registered-event-derived-key[]
