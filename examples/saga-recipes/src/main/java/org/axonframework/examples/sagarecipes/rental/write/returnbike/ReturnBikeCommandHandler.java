package org.axonframework.examples.sagarecipes.rental.write.returnbike;

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

@Component
class ReturnBikeCommandHandler {
    @CommandHandler
    void handle(ReturnBike command, @InjectEntity Bike bike, EventAppender appender) {
        if (bike.available) {
            return;
        }
        appender.append(new BikeReturned(command.bikeId(), bike.rentalId, bike.renter, command.location()));
    }

    @EventSourced(tagKey = RentalTags.BIKE_ID, idType = BikeId.class)
    static class Bike {
        private boolean available;
        private String renter;
        private RentalId rentalId;

        @ForcedEntityCreator Bike() { }
        @EventSourcingHandler void evolve(BikeRegistered event) { available = true; }
        @EventSourcingHandler void evolve(BikeRequested event) {
            available = false;
            renter = event.renter();
            rentalId = event.rentalId();
        }
        @EventSourcingHandler void evolve(BikeInUse event) { }
        @EventSourcingHandler void evolve(RequestRejected event) { available = true; renter = null; rentalId = null; }
        @EventSourcingHandler void evolve(BikeReturned event) { available = true; renter = null; rentalId = null; }
    }
}
