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

package org.axonframework.examples.sagarecipes.rental.write.approverequest;

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

/**
 * Confirms a rental request once its payment has been paid.
 * <p>
 * The saga sends this command from an event handler, so it arrives at least once. The guard is Axon Framework 4's,
 * unchanged, and it is what makes redelivery harmless: a request that is no longer pending, or that belongs to a
 * different renter, appends nothing and reports success.
 *
 * @author Axon Framework
 * @since 5.4.0
 */
@Component
public class ApproveRequestCommandHandler {

    /**
     * Confirms the reservation, unless it is already confirmed or held by someone else.
     *
     * @param command  the command to handle
     * @param state    the decision model for the targeted bike
     * @param appender appends the resulting event
     */
    @CommandHandler
    public void handle(ApproveRequest command, @InjectEntity State state, EventAppender appender) {
        if (!Objects.equals(state.reservedBy, command.renter()) || state.reservationConfirmed) {
            return;
        }
        appender.append(new BikeInUse(command.bikeId(), command.renter(), state.activeRental));
    }

    /**
     * Decision model for this slice: who currently holds the bike, under which rental, and whether that reservation
     * has already been confirmed.
     * <p>
     * The {@code activeRental} field exists only so the outgoing event can carry the rental identifier the command
     * does not supply. Splitting a {@code Rental} entity out of this context would remove that need, and with it
     * most of the saga's reason to hold state.
     */
    @EventSourced(idType = BikeId.class, tagKey = RentalTags.BIKE_ID)
    static class State {

        private String reservedBy;
        private RentalId activeRental;
        private boolean reservationConfirmed;

        @EntityCreator
        State() {
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
    }
}
