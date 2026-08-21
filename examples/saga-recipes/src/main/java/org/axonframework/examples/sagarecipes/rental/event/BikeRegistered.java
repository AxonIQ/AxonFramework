package org.axonframework.examples.sagarecipes.rental.event;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalEvent;
import org.axonframework.examples.sagarecipes.rental.RentalTags;

public record BikeRegistered(@EventTag(key = RentalTags.BIKE_ID) BikeId bikeId,
                             String bikeType,
                             String location) implements RentalEvent {
}
