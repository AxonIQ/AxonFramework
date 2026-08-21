package org.axonframework.examples.sagarecipes.saga.deadline;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;

import java.time.Instant;

@Entity
@Table(name = "pending_payment", indexes = @Index(name = "idx_pending_payment_prepared_at",
                                                   columnList = "preparedAt"))
public class PendingPayment {
    @Id
    private String paymentReference;
    private Instant preparedAt;

    protected PendingPayment() {
    }

    public PendingPayment(PaymentReference paymentReference, Instant preparedAt) {
        this.paymentReference = paymentReference.raw();
        this.preparedAt = preparedAt;
    }

    public PaymentReference paymentReference() {
        return PaymentReference.of(paymentReference);
    }

    public Instant preparedAt() {
        return preparedAt;
    }
}
