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

package org.axonframework.examples.sagarecipes.saga.automations.whenbikerequestedthenpreparepayment;

import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPricing;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Asks for payment whenever a bike is requested.
 * <p>
 * Completely stateless, and worth pausing on. There is no check for whether payment was already asked for, because
 * there is nowhere to check and no need: the reference is derived from the rental, and the payment context refuses to
 * prepare a second payment under a reference it already knows. Idempotency lives where the decision lives.
 * <p>
 * That leaves the processor's tracking token as the entire to-do list. Everything before it has been asked for,
 * everything after it has not, and a redelivery is harmless.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "automations")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class WhenBikeRequestedThenPreparePayment {

    /**
     * Asks the payment context to set up a payment for this rental.
     *
     * @param event      the bike request
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> react(BikeRequested event, CommandDispatcher dispatcher) {
        var reference = RentalPaymentReference.forRental(event.rentalId());
        return dispatcher.send(new PreparePayment(reference, RentalPricing.PRICE))
                         .getResultMessage();
    }
}
