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
import sagas.statecontextevents.RentalPaymentIdResolver;

import java.util.concurrent.CompletableFuture;

import static sagas.shared.RentalPaymentApi.RENTAL_ID;

// tag::with-lookup[]
@Component
@SequencingPolicy(type = PropertySequencingPolicy.class, parameters = "paymentReference") // <1>
public class WhenPaymentConfirmedThenApproveRequest {

    @EventHandler
    CompletableFuture<?> react(
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
    private static class RequestedRental { // <3>

        private final String bikeId;
        private final String renter;

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
