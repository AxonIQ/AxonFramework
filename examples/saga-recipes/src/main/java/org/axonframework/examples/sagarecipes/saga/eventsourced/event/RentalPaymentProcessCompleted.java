package org.axonframework.examples.sagarecipes.saga.eventsourced.event;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;

public record RentalPaymentProcessCompleted(@EventTag(key = RentalTags.RENTAL_ID) RentalId rentalId,
                                            Outcome outcome) {
}
