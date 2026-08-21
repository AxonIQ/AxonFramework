package org.axonframework.examples.sagarecipes.saga.eventsourced;

import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.modelling.annotation.TargetEntityId;

public record RecordPaymentRequested(@TargetEntityId RentalId rentalId, BikeId bikeId, String renter, Amount amount) {
}
