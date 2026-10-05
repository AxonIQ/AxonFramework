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

package sagas.deadlines;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import sagas.shared.RentalPaymentApi.CancelRentalPayment;
import sagas.shared.RentalPaymentApi.PaymentCancelled;
import sagas.shared.RentalPaymentApi.PaymentConfirmed;
import sagas.shared.RentalPaymentApi.PaymentPrepared;
import sagas.shared.RentalPaymentApi.PaymentRejected;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static sagas.shared.RentalPaymentApi.rentalIdFor;

/**
 * Replacing a deadline with a projection of outstanding work plus a periodic sweep.
 */
// tag::sweeper[]
@Component
public class PaymentsAwaitingConfirmation {

    private static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(15);

    private final PendingPaymentRepository pending;
    private final CommandGateway commandGateway;

    public PaymentsAwaitingConfirmation(PendingPaymentRepository pending, CommandGateway commandGateway) {
        this.pending = pending;
        this.commandGateway = commandGateway;
    }

    @EventHandler
    public void on(PaymentPrepared event, EventMessage message) {
        pending.save(new PendingPayment(event.paymentReference(), message.timestamp())); // <1>
    }

    @EventHandler
    public void on(PaymentConfirmed event) {
        pending.deleteById(event.paymentReference()); // <2>
    }

    @EventHandler
    public void on(PaymentRejected event) {
        pending.deleteById(event.paymentReference());
    }

    @EventHandler
    public void on(PaymentCancelled event) {
        pending.deleteById(event.paymentReference());
    }

    @Scheduled(fixedDelayString = "PT5S")
    public void tick() { // <3>
        cancelOverduePayments(Instant.now());
    }

    public void cancelOverduePayments(Instant now) { // <4>
        pending.findByPreparedAtBefore(now.minus(PAYMENT_TIMEOUT))
               .forEach(payment -> commandGateway.sendAndWait(
                       new CancelRentalPayment(rentalIdFor(payment.paymentReference()))
               ));
    }
}
// end::sweeper[]

// tag::todo-list[]
@Entity
@Table(
        name = "pending_payment",
        indexes = @Index(name = "idx_pending_payment_prepared_at", columnList = "preparedAt") // <1>
)
class PendingPayment {

    @Id
    private String paymentReference;
    private Instant preparedAt;

    protected PendingPayment() {
    }

    PendingPayment(String paymentReference, Instant preparedAt) {
        this.paymentReference = paymentReference;
        this.preparedAt = preparedAt;
    }

    String paymentReference() {
        return paymentReference;
    }
}

interface PendingPaymentRepository {

    List<PendingPayment> findByPreparedAtBefore(Instant cutoff); // <2>

    PendingPayment save(PendingPayment payment);

    void deleteById(String paymentReference);
}
// end::todo-list[]
