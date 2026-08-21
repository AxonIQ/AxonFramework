package org.axonframework.examples.sagarecipes.rental.write.approverequest;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.modelling.annotation.TargetEntityId;

public record ApproveRequest(@TargetEntityId BikeId bikeId, String renter) {
}
