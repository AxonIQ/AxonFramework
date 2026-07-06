package migration.paths.aggregates.eventtagging.singletag;

import org.axonframework.eventsourcing.annotation.EventTag;

// tag::bike-requested-event[]
// DCB-style: an event tagged for multiple entities (only after migrating to DCB).
public record BikeRequestedEvent(
        @EventTag(key = "Bike") String bikeId,
        String renter,
        @EventTag(key = "Rental") String rentalReference
) { }
// end::bike-requested-event[]
