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

package org.axonframework.examples.sagarecipes.saga.eventsourced;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentProcessCompleted;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentRequested;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * The process, sourced from its own events and nothing else.
 * <p>
 * Shared by both variants in this package, because the decision model is the thing they agree on. They differ only in
 * how a fact gets written down, and the model that reads those facts back is the same either way. Seeing one state
 * class serve both is the clearest statement that the choice between them is a mechanical one.
 * <p>
 * Compare the derived-state recipe, whose criteria spans both contexts. This one selects only what the process itself
 * wrote, which is exactly why it works when the other side's events are out of reach.
 * <p>
 * The entity carries a condition of its own so that it is registered only under the two recipes that inject it. Both
 * of them do, which is why it is an expression rather than a single {@code havingValue}.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@ConditionalOnExpression(
        "'${saga.recipe:none}' == 'eventsourced' or '${saga.recipe:none}' == 'eventsourced-append'"
)
@EventSourced(idType = RentalId.class)
class ProcessState {

    private final RentalId rentalId;
    private final BikeId bikeId;
    private final String renter;
    private boolean completed;

    @EntityCreator
    ProcessState(RentalPaymentRequested event) {
        this.rentalId = event.rentalId();
        this.bikeId = event.bikeId();
        this.renter = event.renter();
    }

    @EventSourcingHandler
    void evolve(RentalPaymentProcessCompleted event) {
        this.completed = true;
    }

    /**
     * Selects this process's own events, and only those.
     *
     * @param rentalId the rental this process concerns
     * @return the criteria selecting exactly the events this process wrote
     */
    @EventCriteriaBuilder
    private static EventCriteria criteria(RentalId rentalId) {
        return EventCriteria.havingTags(Tag.of(RentalTags.RENTAL_ID, rentalId.raw()))
                            .andBeingOneOfTypes(RentalPaymentRequested.class.getName(),
                                                RentalPaymentProcessCompleted.class.getName());
    }

    RentalId rentalId() {
        return rentalId;
    }

    BikeId bikeId() {
        return bikeId;
    }

    String renter() {
        return renter;
    }

    boolean completed() {
        return completed;
    }
}
