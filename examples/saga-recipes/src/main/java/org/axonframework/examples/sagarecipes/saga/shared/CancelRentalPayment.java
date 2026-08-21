package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.modelling.annotation.TargetEntityId;

public record CancelRentalPayment(@TargetEntityId RentalId rentalId) {
}
