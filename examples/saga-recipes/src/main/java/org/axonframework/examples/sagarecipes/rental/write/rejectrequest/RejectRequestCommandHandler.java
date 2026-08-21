package org.axonframework.examples.sagarecipes.rental.write.rejectrequest;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.BikeReturned;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
class RejectRequestCommandHandler {
    @CommandHandler
    void handle(RejectRequest command, @InjectEntity Bike bike, EventAppender appender) {
        if (!Objects.equals(bike.renter, command.renter()) || bike.confirmed) {
            return;
        }
        appender.append(new RequestRejected(command.bikeId(), bike.rentalId, command.renter()));
    }

    @EventSourced(tagKey = RentalTags.BIKE_ID, idType = BikeId.class)
    static class Bike {
        private String renter;
        private RentalId rentalId;
        private boolean confirmed;

        @EntityCreator Bike() { }
        @EventSourcingHandler void evolve(BikeRequested event) {
            renter = event.renter();
            rentalId = event.rentalId();
            confirmed = false;
        }
        @EventSourcingHandler void evolve(BikeInUse event) { confirmed = true; }
        @EventSourcingHandler void evolve(RequestRejected event) { renter = null; rentalId = null; }
        @EventSourcingHandler void evolve(BikeReturned event) { renter = null; rentalId = null; confirmed = false; }
    }
}
