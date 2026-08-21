package org.axonframework.examples.sagarecipes.rental.write.registerbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.modelling.annotation.TargetEntityId;

public record RegisterBike(@TargetEntityId BikeId bikeId, String bikeType, String location) {
}
