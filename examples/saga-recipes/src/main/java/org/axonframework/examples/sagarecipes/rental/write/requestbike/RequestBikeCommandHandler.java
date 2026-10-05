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

package org.axonframework.examples.sagarecipes.rental.write.requestbike;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reserves a bike for a renter, starting the rental payment process.
 * <p>
 * This slice enforces three things that no single entity could enforce on its own: the bike must be free, the renter
 * must not already hold a bike, and this exact request must not have been handled before. It therefore sources across
 * three tags and appends against the union of all of them, which is what a Dynamic Consistency Boundary is for.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
public class RequestBikeCommandHandler {

    /**
     * Reserves the bike, unless this exact request was already handled or a rule forbids it.
     *
     * @param command  the command to handle
     * @param state    the decision model spanning the bike, the renter and this request, or {@code null} if none of
     *                 the three has any history
     * @param appender appends the resulting event
     */
    @CommandHandler
    void handle(RequestBike command, @InjectEntity @Nullable State state, EventAppender appender) {
        if (state == null || !state.bikeRegistered) {
            throw new IllegalStateException("Bike is not registered");
        }
        if (state.alreadyRequested) {
            return;
        }
        if (!state.bikeAvailable) {
            throw new IllegalStateException("Bike is already rented");
        }
        if (state.renterHoldsABike) {
            throw new IllegalStateException("Renter already holds a bike");
        }
        appender.append(new BikeRequested(command.bikeId(), command.renter(), command.rentalId()));
    }

    /**
     * Decision model spanning one bike, one renter and one rental request.
     * <p>
     * The entity learns its own identifier through {@link InjectEntityId}, which is what lets a single evolve method
     * tell "an event about my bike" apart from "an event about my renter" apart from "an event about my request". All
     * three criterions feed one stream, so the event itself cannot say which of them selected it.
     * <p>
     * A plain {@code @EntityCreator} is only invoked once the stream holds at least one selected event, so an
     * untouched bike, renter and request yield no entity at all. The handler therefore takes a nullable parameter and
     * treats {@code null} the same way it treats a bike with no registration event.
     */
    @EventSourced(idType = RentalRequestId.class)
    private static class State {

        private final RentalRequestId id;

        private boolean bikeRegistered;
        private boolean bikeAvailable;
        private boolean renterHoldsABike;
        private boolean alreadyRequested;

        @EntityCreator
        State(@InjectEntityId RentalRequestId id) {
            this.id = id;
        }

        @EventSourcingHandler
        void evolve(BikeRegistered event) {
            this.bikeRegistered = true;
            this.bikeAvailable = true;
        }

        @EventSourcingHandler
        void evolve(BikeRequested event) {
            if (concernsMyBike(event.bikeId())) {
                this.bikeAvailable = false;
            }
            if (concernsMyRenter(event.renter())) {
                this.renterHoldsABike = true;
            }
            if (concernsMyRequest(event.rentalId())) {
                this.alreadyRequested = true;
            }
        }

        @EventSourcingHandler
        void evolve(RequestRejected event) {
            releaseOn(event.bikeId(), event.renter());
        }

        @EventSourcingHandler
        void evolve(BikeReturned event) {
            releaseOn(event.bikeId(), event.renter());
        }

        /**
         * Frees the bike and the renter again.
         * <p>
         * Deliberately does not touch {@code alreadyRequested}. Availability is a fact about now; having handled a
         * request is a fact forever. Clearing it would let a redelivered command produce a second reservation under
         * the same {@code rentalId} once the first rental had ended.
         */
        private void releaseOn(BikeId bikeId, String renter) {
            if (concernsMyBike(bikeId)) {
                this.bikeAvailable = true;
            }
            if (concernsMyRenter(renter)) {
                this.renterHoldsABike = false;
            }
        }

        private boolean concernsMyBike(BikeId bikeId) {
            return id.bikeId().equals(bikeId);
        }

        private boolean concernsMyRenter(String renter) {
            return id.renter().equals(renter);
        }

        private boolean concernsMyRequest(RentalId rentalId) {
            return id.rentalId().equals(rentalId);
        }

        /**
         * Selects the bike's own history, this renter's history across every bike, and this request's own event.
         * <p>
         * The bike and renter criterions are deliberately symmetric: every event type that makes a bike unavailable
         * or a renter busy also appears alongside the event types that free them again. Leaving a releasing event
         * type out would let a concurrent return slip past the append condition and permit a second simultaneous
         * rental.
         * <p>
         * The third criterion selects at most one event, and is what makes this command idempotent for its whole
         * lifetime rather than only while the rental is active. Because it also joins the append condition, two
         * concurrent deliveries of the same request conflict in the event store instead of racing past a read-time
         * guard.
         * <p>
         * {@code BikeInUse} is absent on purpose. It changes no flag, so including it would only widen the conflict
         * surface.
         *
         * @param id the composite identifier of this decision model
         * @return the criteria selecting exactly the events this decision depends on
         */
        @EventCriteriaBuilder
        private static EventCriteria criteria(RentalRequestId id) {
            return EventCriteria.either(
                    EventCriteria.havingTags(Tag.of(RentalTags.BIKE_ID, id.bikeId().raw()))
                                 .andBeingOneOfTypes(BikeRegistered.class.getName(),
                                                     BikeRequested.class.getName(),
                                                     RequestRejected.class.getName(),
                                                     BikeReturned.class.getName()),
                    EventCriteria.havingTags(Tag.of(RentalTags.RENTER, id.renter()))
                                 .andBeingOneOfTypes(BikeRequested.class.getName(),
                                                     RequestRejected.class.getName(),
                                                     BikeReturned.class.getName()),
                    EventCriteria.havingTags(Tag.of(RentalTags.RENTAL_ID, id.rentalId().raw()))
                                 .andBeingOneOfTypes(BikeRequested.class.getName())
            );
        }
    }
}
