package org.axonframework.examples.sagarecipes.rental.write.registerbike;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeRegistered;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class RegisterBikeCommandHandler {
    @CommandHandler
    void handle(RegisterBike command, @Nullable @InjectEntity Bike bike, EventAppender appender) {
        if (bike == null) {
            appender.append(new BikeRegistered(command.bikeId(), command.bikeType(), command.location()));
        }
    }

    @EventSourced(tagKey = RentalTags.BIKE_ID, idType = BikeId.class)
    static class Bike {
        @EntityCreator
        Bike() {
        }

        @EventSourcingHandler
        void evolve(BikeRegistered event) {
            // The entity's presence records that the bike was registered.
        }
    }
}
