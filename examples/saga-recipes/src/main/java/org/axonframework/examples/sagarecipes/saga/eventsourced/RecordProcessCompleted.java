package org.axonframework.examples.sagarecipes.saga.eventsourced;

import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.Outcome;
import org.axonframework.modelling.annotation.TargetEntityId;

public record RecordProcessCompleted(@TargetEntityId RentalId rentalId, Outcome outcome) {
}
