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

package sagas.verticalslices;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import sagas.shared.RentalPaymentApi.ApproveRequest;
import sagas.shared.RentalPaymentApi.BikeRequested;
import sagas.shared.RentalPaymentApi.PaymentConfirmed;
import sagas.shared.RentalPaymentApi.PreparePayment;
import sagas.statecontextevents.RentalPaymentIdResolver;

import java.util.concurrent.CompletableFuture;

import static sagas.shared.RentalPaymentApi.PRICE;
import static sagas.shared.RentalPaymentApi.RENTAL_ID;
import static sagas.shared.RentalPaymentApi.paymentReferenceFor;

/**
 * Taking the process apart into independent vertical slices (automation slices in Event Modelling terms).
 */
public class VerticalSlices {

    // tag::stateless[]
    @Component
    @SequencingPolicy(type = PropertySequencingPolicy.class, parameters = "rentalId") // <1>
    public static class WhenBikeRequestedThenPreparePayment {

        @EventHandler
        public CompletableFuture<?> react(BikeRequested event, CommandDispatcher dispatcher) {
            return dispatcher.send(new PreparePayment(paymentReferenceFor(event.rentalId()), PRICE))
                             .getResultMessage(); // <2>
        }
    }
    // end::stateless[]

    // tag::with-lookup[]
    @Component
    @SequencingPolicy(type = PropertySequencingPolicy.class, parameters = "paymentReference") // <1>
    public static class WhenPaymentConfirmedThenApproveRequest {

        @EventHandler
        public CompletableFuture<?> react(
                PaymentConfirmed event,
                @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable RequestedRental rental, // <2>
                CommandDispatcher dispatcher
        ) {
            if (rental == null) {
                return CompletableFuture.completedFuture(null);
            }
            return dispatcher.send(new ApproveRequest(rental.bikeId, rental.renter))
                             .getResultMessage();
        }

        @EventSourced(idType = String.class)
        static class RequestedRental { // <3>

            String bikeId;
            String renter;

            @EntityCreator
            RequestedRental(BikeRequested event) {
                this.bikeId = event.bikeId();
                this.renter = event.renter();
            }

            @EventCriteriaBuilder
            private static EventCriteria criteria(String rentalId) {
                return EventCriteria.havingTags(Tag.of(RENTAL_ID, rentalId))
                                    .andBeingOneOfTypes(BikeRequested.class.getName());
            }
        }
    }
    // end::with-lookup[]
}
