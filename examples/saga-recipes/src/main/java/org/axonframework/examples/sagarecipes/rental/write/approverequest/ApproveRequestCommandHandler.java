package org.axonframework.examples.sagarecipes.rental.write.approverequest;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.ForcedEntityCreator;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
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
class ApproveRequestCommandHandler {
    @CommandHandler
    void handle(ApproveRequest command, @InjectEntity Bike bike, EventAppender appender) {
        if (!Objects.equals(bike.renter, command.renter()) || bike.confirmed) {
            return;
        }
        appender.append(new BikeInUse(command.bikeId(), command.renter(), bike.rentalId));
    }

    @EventSourced(tagKey = RentalTags.BIKE_ID, idType = BikeId.class)
    static class Bike {
        private String renter;
        private RentalId rentalId;
        private boolean confirmed;

        @ForcedEntityCreator
        Bike() {
        }

        @EventSourcingHandler void evolve(BikeRegistered event) { }
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
