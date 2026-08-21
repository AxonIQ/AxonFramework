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

package org.axonframework.examples.sagarecipes.saga.automations.whenrequestrejectedthencancelpayment;

import org.axonframework.examples.sagarecipes.payment.write.cancelpayment.CancelPayment;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Calls off the payment whenever a request is turned down.
 * <p>
 * The compensating direction, and what replaces version 4's {@code cancelAllWithinScope}: without it, a rental
 * rejected on grounds of its own would leave a payment outstanding forever.
 * <p>
 * Stateless like its counterpart. Cancelling a payment that already settled, or that was never prepared, is a silent
 * success in the payment context, so this slice can send the command without knowing which case it is in.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "automations")
@SequencingPolicy(type = PropertySequencingPolicy.class, parameters = "rentalId")
public class WhenRequestRejectedThenCancelPayment {

    /**
     * Tells the payment context to stop waiting for this rental's payment.
     *
     * @param event      the rejected request
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> react(RequestRejected event, CommandDispatcher dispatcher) {
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId())))
                         .getResultMessage();
    }
}
