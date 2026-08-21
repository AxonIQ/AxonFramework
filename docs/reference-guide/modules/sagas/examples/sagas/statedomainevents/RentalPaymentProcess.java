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

package sagas.statedomainevents;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import sagas.shared.RentalPaymentApi.ApproveRequest;
import sagas.shared.RentalPaymentApi.BikeInUse;
import sagas.shared.RentalPaymentApi.BikeRequested;
import sagas.shared.RentalPaymentApi.PaymentCancelled;
import sagas.shared.RentalPaymentApi.PaymentConfirmed;
import sagas.shared.RentalPaymentApi.PaymentPrepared;
import sagas.shared.RentalPaymentApi.PaymentRejected;
import sagas.shared.RentalPaymentApi.PreparePayment;
import sagas.shared.RentalPaymentApi.RequestRejected;

import java.util.concurrent.CompletableFuture;

import static sagas.shared.RentalPaymentApi.PAYMENT_REFERENCE;
import static sagas.shared.RentalPaymentApi.PRICE;
import static sagas.shared.RentalPaymentApi.RENTAL_ID;
import static sagas.shared.RentalPaymentApi.paymentReferenceFor;

/**
 * Rebuilding the process state from events both contexts have already written, so the process stores nothing at all.
 */
// tag::process[]
@Component
public class RentalPaymentProcess {

    @EventHandler
    public CompletableFuture<?> on(
            BikeRequested event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state, // <1>
            CommandDispatcher dispatcher
    ) {
        if (state != null && state.paymentRequested) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new PreparePayment(paymentReferenceFor(event.rentalId()), PRICE))
                         .getResultMessage();
    }

    @EventHandler
    public CompletableFuture<?> on(
            PaymentConfirmed event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        if (state == null || state.requestSettled) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new ApproveRequest(state.bikeId, state.renter)) // <2>
                         .getResultMessage();
    }
}
// end::process[]

// tag::state[]
@EventSourced(idType = String.class)
class State {

    String bikeId;
    String renter;
    boolean paymentRequested;
    boolean requestSettled;

    @EntityCreator
    State(BikeRequested event) {
        this.bikeId = event.bikeId();
        this.renter = event.renter();
    }

    @EventSourcingHandler
    void evolve(PaymentPrepared event) {
        this.paymentRequested = true;
    }

    @EventSourcingHandler
    void evolve(BikeInUse event) {
        this.requestSettled = true;
    }

    @EventSourcingHandler
    void evolve(RequestRejected event) {
        this.requestSettled = true;
    }

    @EventCriteriaBuilder
    private static EventCriteria criteria(String rentalId) {
        return EventCriteria.either(
                EventCriteria.havingTags(Tag.of(RENTAL_ID, rentalId)) // <1>
                             .andBeingOneOfTypes(BikeRequested.class.getName(),
                                                 BikeInUse.class.getName(),
                                                 RequestRejected.class.getName()),
                EventCriteria.havingTags(Tag.of(PAYMENT_REFERENCE, paymentReferenceFor(rentalId))) // <2>
                             .andBeingOneOfTypes(PaymentPrepared.class.getName(),
                                                 PaymentConfirmed.class.getName(),
                                                 PaymentRejected.class.getName(),
                                                 PaymentCancelled.class.getName())
        );
    }
}
// end::state[]
