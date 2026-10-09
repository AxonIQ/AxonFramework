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
package messagescheduling.commands;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.modelling.annotation.TargetEntityId;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// tag::cancel-rental-payment[]
record CancelRentalPayment(@TargetEntityId String paymentReference) {

}
// end::cancel-rental-payment[]

// tag::schedule-command[]
public class PaymentTimeouts {

    private final ScheduledExecutorService trigger = Executors.newSingleThreadScheduledExecutor(); // <1>
    private final CommandGateway commandGateway;

    public PaymentTimeouts(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    public void scheduleTimeout(String paymentReference, Duration timeout) {
        trigger.schedule(
                () -> commandGateway.send(new CancelRentalPayment(paymentReference)), // <2>
                timeout.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }
}
// end::schedule-command[]

// tag::cancel-rental-payment-handler[]
class RentalPayments {

    private final Set<String> confirmedPayments = ConcurrentHashMap.newKeySet();

    public void confirm(String paymentReference) {
        confirmedPayments.add(paymentReference);
    }

    @CommandHandler
    public PaymentCancellationResult on(CancelRentalPayment command) {

        return confirmedPayments.contains(command.paymentReference())
                ? PaymentCancellationResult.refused() // <1>
                : PaymentCancellationResult.cancelled(); // <2>
    }
}
// end::cancel-rental-payment-handler[]
