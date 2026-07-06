package migration.paths.aggregates.eventtagging.singletag;

import org.axonframework.eventsourcing.annotation.EventTag;

// tag::bike-returned-event[]
// Architecture-neutral migration: ONE tag, matching the entity's tagKey.
public record BikeReturnedEvent(
        @EventTag(key = "Bike") String bikeId,
        String location
) { }

// end::bike-returned-event[]
