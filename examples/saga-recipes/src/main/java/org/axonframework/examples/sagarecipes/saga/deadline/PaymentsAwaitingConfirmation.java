package org.axonframework.examples.sagarecipes.saga.deadline;

import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.saga.shared.CancelRentalPayment;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.annotation.Timestamp;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static org.axonframework.examples.sagarecipes.saga.shared.SagaConstants.PAYMENT_TIMEOUT;

@Component
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class PaymentsAwaitingConfirmation {
    private final PendingPaymentRepository pending;
    private final CommandGateway commandGateway;

    public PaymentsAwaitingConfirmation(PendingPaymentRepository pending, CommandGateway commandGateway) {
        this.pending = pending;
        this.commandGateway = commandGateway;
    }

    @EventHandler
    void on(PaymentPrepared event, @Timestamp Instant preparedAt) {
        pending.save(new PendingPayment(event.paymentReference(), preparedAt));
    }

    @EventHandler
    void on(PaymentConfirmed event) {
        pending.deleteById(event.paymentReference().raw());
    }

    @EventHandler
    void on(PaymentRejected event) {
        pending.deleteById(event.paymentReference().raw());
    }

    @EventHandler
    void on(PaymentCancelled event) {
        pending.deleteById(event.paymentReference().raw());
    }

    @Scheduled(fixedDelayString = "${saga.deadline.sweep-interval:PT5S}",
            initialDelayString = "${saga.deadline.initial-delay:PT5S}")
    void tick() {
        cancelOverduePayments(Instant.now());
    }

    public void cancelOverduePayments(Instant now) {
        pending.findByPreparedAtBefore(now.minus(PAYMENT_TIMEOUT)).forEach(payment ->
                commandGateway.send(new CancelRentalPayment(
                        RentalPaymentReference.toRental(payment.paymentReference()))));
    }
}
