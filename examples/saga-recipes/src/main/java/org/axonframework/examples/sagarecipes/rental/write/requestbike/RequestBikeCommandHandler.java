package org.axonframework.examples.sagarecipes.rental.write.requestbike;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
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
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

@Component
class RequestBikeCommandHandler {
    @CommandHandler
    void handle(RequestBike command, @InjectEntity State state, EventAppender appender) {
        if (command.rentalId().equals(state.activeRentalId)) {
            return;
        }
        if (!state.bikeAvailable) {
            throw new IllegalStateException("Bike is not available");
        }
        if (state.renterHoldsABike) {
            throw new IllegalStateException("Renter already holds a bike");
        }
        appender.append(new BikeRequested(command.bikeId(), command.renter(), command.rentalId()));
    }

    @EventSourced(idType = RentalRequestId.class)
    static class State {
        private final RentalRequestId id;
        private boolean bikeAvailable;
        private RentalId activeRentalId;
        private boolean renterHoldsABike;

        @EntityCreator
        State(@InjectEntityId RentalRequestId id) {
            this.id = id;
        }

        @EventSourcingHandler
        void evolve(BikeRegistered event) {
            if (event.bikeId().equals(id.bikeId())) {
                bikeAvailable = true;
            }
        }

        @EventSourcingHandler
        void evolve(BikeRequested event) {
            if (event.bikeId().equals(id.bikeId())) {
                bikeAvailable = false;
                activeRentalId = event.rentalId();
            }
            if (event.renter().equals(id.renter())) {
                renterHoldsABike = true;
            }
        }

        @EventSourcingHandler
        void evolve(BikeInUse event) {
            // The bike stays unavailable and the renter keeps holding it.
        }

        @EventSourcingHandler
        void evolve(RequestRejected event) {
            clearIfCorrelated(event.bikeId(), event.renter());
        }

        @EventSourcingHandler
        void evolve(BikeReturned event) {
            clearIfCorrelated(event.bikeId(), event.renter());
        }

        private void clearIfCorrelated(org.axonframework.examples.sagarecipes.rental.BikeId bikeId, String renter) {
            if (bikeId.equals(id.bikeId())) {
                bikeAvailable = true;
                activeRentalId = null;
            }
            if (renter.equals(id.renter())) {
                renterHoldsABike = false;
            }
        }

        @EventCriteriaBuilder
        static EventCriteria criteria(RentalRequestId id) {
            return EventCriteria.either(
                    EventCriteria.havingTags(Tag.of(RentalTags.BIKE_ID, id.bikeId().raw()))
                                 .andBeingOneOfTypes(BikeRegistered.class.getName(), BikeRequested.class.getName(),
                                                    BikeInUse.class.getName(), RequestRejected.class.getName(),
                                                    BikeReturned.class.getName()),
                    EventCriteria.havingTags(Tag.of(RentalTags.RENTER, id.renter()))
                                 .andBeingOneOfTypes(BikeRequested.class.getName(), BikeInUse.class.getName(),
                                                    RequestRejected.class.getName(), BikeReturned.class.getName())
            );
        }
    }
}
