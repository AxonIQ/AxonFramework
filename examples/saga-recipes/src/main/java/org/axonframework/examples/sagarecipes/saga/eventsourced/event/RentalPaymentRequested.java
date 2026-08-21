package org.axonframework.examples.sagarecipes.saga.eventsourced.event;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;

public record RentalPaymentRequested(@EventTag(key = RentalTags.RENTAL_ID) RentalId rentalId,
                                     BikeId bikeId,
                                     String renter,
                                     Amount amount) {
}
