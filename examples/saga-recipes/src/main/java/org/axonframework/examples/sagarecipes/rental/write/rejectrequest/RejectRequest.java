package org.axonframework.examples.sagarecipes.rental.write.rejectrequest;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.modelling.annotation.TargetEntityId;

public record RejectRequest(@TargetEntityId BikeId bikeId, String renter) {
}
