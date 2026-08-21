package org.axonframework.examples.sagarecipes.rental.write.returnbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.modelling.annotation.TargetEntityId;

public record ReturnBike(@TargetEntityId BikeId bikeId, String location) {
}
