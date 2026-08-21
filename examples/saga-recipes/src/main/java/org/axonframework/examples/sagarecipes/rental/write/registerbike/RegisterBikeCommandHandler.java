package org.axonframework.examples.sagarecipes.rental.write.registerbike;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.ForcedEntityCreator;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

@Component
class RegisterBikeCommandHandler {
    @CommandHandler
    void handle(RegisterBike command, @InjectEntity Bike bike, EventAppender appender) {
        if (!bike.registered) {
            appender.append(new BikeRegistered(command.bikeId(), command.bikeType(), command.location()));
        }
    }

    @EventSourced(tagKey = RentalTags.BIKE_ID, idType = BikeId.class)
    static class Bike {
        private boolean registered;

        @ForcedEntityCreator
        Bike() {
        }

        @EventSourcingHandler
        void evolve(BikeRegistered event) {
            registered = true;
        }
    }
}
