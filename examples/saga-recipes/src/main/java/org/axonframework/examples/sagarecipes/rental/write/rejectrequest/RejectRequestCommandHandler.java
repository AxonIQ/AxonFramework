/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.examples.sagarecipes.rental.write.rejectrequest;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
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
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Turns down a rental request and releases the bike.
 * <p>
 * Mirrors {@code ApproveRequestCommandHandler}: same guard, opposite outcome. Rejecting a request that is already
 * settled, or that belongs to another renter, appends nothing and reports success, which is what makes it safe for
 * the saga to send more than once.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
public class RejectRequestCommandHandler {

    /**
     * Releases the bike, unless the reservation is already settled or held by someone else.
     *
     * @param command  the command to handle
     * @param state    the bike's reservation, or {@code null} if the bike was never requested
     * @param appender appends the resulting event
     */
    @CommandHandler
    void handle(RejectRequest command, @InjectEntity @Nullable State state, EventAppender appender) {
        if (state == null || !Objects.equals(state.reservedBy, command.renter()) || state.reservationConfirmed) {
            return;
        }
        appender.append(new RequestRejected(command.bikeId(), command.renter(), state.activeRental));
    }

    /**
     * Decision model for this slice: who holds the bike, under which rental, and whether the reservation was already
     * confirmed.
     */
    @EventSourced(idType = BikeId.class)
    private static class State {

        private String reservedBy;
        private RentalId activeRental;
        private boolean reservationConfirmed;

        @EntityCreator
        State(BikeRequested event) {
            this.reservedBy = event.renter();
            this.activeRental = event.rentalId();
        }

        @EventSourcingHandler
        void evolve(BikeRequested event) {
            this.reservedBy = event.renter();
            this.activeRental = event.rentalId();
            this.reservationConfirmed = false;
        }

        @EventSourcingHandler
        void evolve(BikeInUse event) {
            this.reservationConfirmed = true;
        }

        @EventSourcingHandler
        void evolve(RequestRejected event) {
            clear();
        }

        @EventSourcingHandler
        void evolve(BikeReturned event) {
            clear();
        }

        private void clear() {
            this.reservedBy = null;
            this.activeRental = null;
            this.reservationConfirmed = false;
        }

        /**
         * Only the reservation matters here. {@code BikeRegistered} is deliberately excluded: whether the bike was
         * ever added to the fleet has no bearing on this decision, and sourcing it would both widen the conflict
         * surface and make the registration event the first one the entity would have to be created from.
         *
         * @param bikeId the bike this decision concerns
         * @return the criteria selecting exactly the events this decision depends on
         */
        @EventCriteriaBuilder
        private static EventCriteria criteria(BikeId bikeId) {
            return EventCriteria.havingTags(Tag.of(RentalTags.BIKE_ID, bikeId.raw()))
                                .andBeingOneOfTypes(BikeRequested.class.getName(),
                                                    BikeInUse.class.getName(),
                                                    RequestRejected.class.getName(),
                                                    BikeReturned.class.getName());
        }
    }
}
