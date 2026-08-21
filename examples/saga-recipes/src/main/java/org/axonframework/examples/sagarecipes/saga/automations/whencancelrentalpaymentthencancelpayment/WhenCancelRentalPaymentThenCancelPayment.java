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

package org.axonframework.examples.sagarecipes.saga.automations.whencancelrentalpaymentthencancelpayment;

import org.axonframework.examples.sagarecipes.payment.write.cancelpayment.CancelPayment;
import org.axonframework.examples.sagarecipes.saga.shared.CancelRentalPayment;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Passes a request to give up waiting on to the payment context.
 * <p>
 * The other recipes answer {@link CancelRentalPayment} by consulting their own state first, to avoid asking for
 * something pointless. This slice does not, and the omission is deliberate. Whether cancelling is still worthwhile is
 * a question about the payment, and the payment context answers it authoritatively; the process's own view can only
 * ever be a stale copy of that answer. Checking here would duplicate a decision that is not this slice's to make, and
 * would still need the payment context's check to be correct under a race.
 * <p>
 * So the whole slice is a translation: a rental-shaped request becomes a payment-shaped one. That is all an
 * automation ever is.
 *
 * @author Axon Framework
 * @since 5.4.0
 */
@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "automations")
public class WhenCancelRentalPaymentThenCancelPayment {

    /**
     * Asks the payment context to stop waiting for this rental's payment.
     *
     * @param command    the request to give up
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @CommandHandler
    public CompletableFuture<?> handle(CancelRentalPayment command, CommandDispatcher dispatcher) {
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(command.rentalId())))
                         .getResultMessage();
    }
}
