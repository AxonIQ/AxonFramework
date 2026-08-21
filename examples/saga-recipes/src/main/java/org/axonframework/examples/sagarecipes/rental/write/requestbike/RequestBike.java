package org.axonframework.examples.sagarecipes.rental.write.requestbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.modelling.annotation.TargetEntityId;

public record RequestBike(BikeId bikeId, String renter, RentalId rentalId) {
    @TargetEntityId
    RentalRequestId target() {
        return new RentalRequestId(bikeId, renter);
    }
}
