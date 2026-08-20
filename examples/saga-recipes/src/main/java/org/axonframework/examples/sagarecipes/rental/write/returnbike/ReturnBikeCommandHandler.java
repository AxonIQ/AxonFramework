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

package org.axonframework.examples.sagarecipes.rental.write.returnbike;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeReturned;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.stereotype.Component;

/**
 * Gives a bike back.
 * <p>
 * Unlike {@code ApproveRequest} and {@code RejectRequest}, this command is not sent by an event processor. It comes
 * from a person, exactly once, so a bike that is not in use is a genuine mistake and is reported as one. Idempotency
 * is a requirement wherever delivery is at-least-once, not a blanket rule for every handler.
 *
 * @author Axon Framework
 * @since 5.3.0
 */
@Component
public class ReturnBikeCommandHandler {

    /**
     * Returns the bike.
     *
     * @param command  the command to handle
     * @param state    the decision model for the targeted bike
     * @param appender appends the resulting event
     * @throws IllegalStateException if the bike is not currently in use
     */
    @CommandHandler
    public void handle(ReturnBike command, @InjectEntity State state, EventAppender appender) {
        if (!state.inUse) {
            throw new IllegalStateException("Bike is not in use");
        }
        appender.append(new BikeReturned(command.bikeId(), state.renter, state.activeRental, command.location()));
    }

    /**
     * Decision model for this slice: whether the bike is out on a confirmed rental, and with whom.
     * <p>
     * Narrower than the neighbouring slices: a merely requested bike cannot be returned, so only
     * {@code BikeInUse} and {@code BikeReturned} matter here.
     */
    @EventSourced(idType = BikeId.class, tagKey = RentalTags.BIKE_ID)
    static class State {

        private boolean inUse;
        private String renter;
        private RentalId activeRental;

        @EntityCreator
        State() {
        }

        @EventSourcingHandler
        void evolve(BikeInUse event) {
            this.inUse = true;
            this.renter = event.renter();
            this.activeRental = event.rentalId();
        }

        @EventSourcingHandler
        void evolve(BikeReturned event) {
            this.inUse = false;
            this.renter = null;
            this.activeRental = null;
        }
    }
}
