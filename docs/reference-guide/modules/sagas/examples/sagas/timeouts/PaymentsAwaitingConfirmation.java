package sagas.timeouts;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.annotation.Timestamp;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class PaymentsAwaitingConfirmation {
    private static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(15);

    private final PendingPaymentRepository pending;
    private final CommandGateway commandGateway;

    PaymentsAwaitingConfirmation(PendingPaymentRepository pending, CommandGateway commandGateway) {
        this.pending = pending;
        this.commandGateway = commandGateway;
    }

    // tag::deadline-projection[]
    @EventHandler
    void on(PaymentPrepared event, @Timestamp Instant preparedAt) {
        pending.save(new PendingPayment(event.paymentReference(), preparedAt));
    }

    @EventHandler
    void on(PaymentConfirmed event) {
        pending.delete(event.paymentReference());
    }

    @EventHandler
    void on(PaymentRejected event) {
        pending.delete(event.paymentReference());
    }

    @EventHandler
    void on(PaymentCancelled event) {
        pending.delete(event.paymentReference());
    }
    // end::deadline-projection[]

    // tag::deadline-sweeper[]
    @Scheduled(fixedDelayString = "${payment.sweep-interval:PT5S}")
    void tick() {
        cancelOverduePayments(Instant.now());
    }

    void cancelOverduePayments(Instant now) {
        pending.findPreparedBefore(now.minus(PAYMENT_TIMEOUT)).forEach(payment ->
                commandGateway.send(new CancelRentalPayment(payment.paymentReference()))
        );
    }
    // end::deadline-sweeper[]
}

interface PendingPaymentRepository {
    void save(PendingPayment payment);

    void delete(String paymentReference);

    List<PendingPayment> findPreparedBefore(Instant instant);
}

record PendingPayment(String paymentReference, Instant preparedAt) {
}

record PaymentPrepared(String paymentId, String paymentReference) {
}

record PaymentConfirmed(String paymentId, String paymentReference) {
}

record PaymentRejected(String paymentId, String paymentReference) {
}

record PaymentCancelled(String paymentId, String paymentReference) {
}

record CancelRentalPayment(String rentalId) {
}
