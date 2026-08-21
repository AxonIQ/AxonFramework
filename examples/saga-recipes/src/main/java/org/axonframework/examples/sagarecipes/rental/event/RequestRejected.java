package org.axonframework.examples.sagarecipes.rental.event;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalEvent;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;

public record RequestRejected(@EventTag(key = RentalTags.BIKE_ID) BikeId bikeId,
                              @EventTag(key = RentalTags.RENTAL_ID) RentalId rentalId,
                              @EventTag(key = RentalTags.RENTER) String renter) implements RentalEvent {
}
