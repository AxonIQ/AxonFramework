package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.rental.RentalId;

public final class RentalPaymentReference {
    private RentalPaymentReference() {
    }

    public static PaymentReference forRental(RentalId rentalId) {
        return PaymentReference.of(rentalId.raw());
    }

    public static RentalId toRental(PaymentReference reference) {
        return RentalId.of(reference.raw());
    }
}
