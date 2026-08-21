package org.axonframework.examples.sagarecipes.rental.write.requestbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;

record RentalRequestId(BikeId bikeId, String renter) {
}
