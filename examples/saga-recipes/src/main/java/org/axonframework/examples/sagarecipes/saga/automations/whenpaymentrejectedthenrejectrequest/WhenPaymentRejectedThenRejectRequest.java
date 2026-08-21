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

package org.axonframework.examples.sagarecipes.saga.automations.whenpaymentrejectedthenrejectrequest;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentIdResolver;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Releases the bike whenever a payment is refused.
 * <p>
 * This slice cannot be stateless. A payment event carries only the reference, and rejecting the request needs the bike and
 * the renter, so the slice keeps a lookup of its own: three lines of Dynamic Consistency Boundary rather than a read
 * model with a table behind it.
 * <p>
 * The lookup is a read. Nothing is written here beyond the command that gets dispatched, so there is still only one
 * effect and nothing that could fall out of step with the tracking token.
 * <p>
 * Note that the lookup is private to this slice. Two neighbouring slices keep the same one, and that duplication is
 * the point: slices are independent, and sharing would couple them.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "automations")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class WhenPaymentRejectedThenRejectRequest {

    /**
     * Turns down the rental request this payment belongs to.
     *
     * @param event      the payment event that triggered this
     * @param rental     the request this payment belongs to, or {@code null} if there is none
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> react(
            PaymentRejected event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable RequestedRental rental,
            CommandDispatcher dispatcher
    ) {
        if (rental == null) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new RejectRequest(rental.bikeId, rental.renter))
                         .getResultMessage();
    }

    /**
     * All this slice needs to know: which bike, and whose.
     */
    @ConditionalOnProperty(name = "saga.recipe", havingValue = "automations")
    @EventSourced(idType = RentalId.class)
    static class RequestedRental {

        private final BikeId bikeId;
        private final String renter;

        @EntityCreator
        RequestedRental(BikeRequested event) {
            this.bikeId = event.bikeId();
            this.renter = event.renter();
        }

        @EventSourcingHandler
        void evolve(BikeRequested event) {
            // Nothing to do: the creator captured everything, and a rental is requested once.
        }

        /**
         * Selects the one event that answers the question.
         *
         * @param rentalId the rental this lookup concerns
         * @return the criteria selecting exactly that event
         */
        @EventCriteriaBuilder
        private static EventCriteria criteria(RentalId rentalId) {
            return EventCriteria.havingTags(Tag.of(RentalTags.RENTAL_ID, rentalId.raw()))
                                .andBeingOneOfTypes(BikeRequested.class.getName());
        }
    }
}
