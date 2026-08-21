package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.sequencing.ExtractionSequencingPolicy;

import java.util.Map;

public class RentalPaymentSequencingPolicy extends QualifiedNameRoutingSequencingPolicy {
    public RentalPaymentSequencingPolicy() {
        super(Map.of(
                new QualifiedName(BikeRequested.class),
                new ExtractionSequencingPolicy<>(BikeRequested.class, event -> event.rentalId().raw()),
                new QualifiedName(BikeInUse.class),
                new ExtractionSequencingPolicy<>(BikeInUse.class, event -> event.rentalId().raw()),
                new QualifiedName(RequestRejected.class),
                new ExtractionSequencingPolicy<>(RequestRejected.class, event -> event.rentalId().raw()),
                new QualifiedName(PaymentPrepared.class),
                new ExtractionSequencingPolicy<>(PaymentPrepared.class, event -> event.paymentReference().raw()),
                new QualifiedName(PaymentConfirmed.class),
                new ExtractionSequencingPolicy<>(PaymentConfirmed.class, event -> event.paymentReference().raw()),
                new QualifiedName(PaymentRejected.class),
                new ExtractionSequencingPolicy<>(PaymentRejected.class, event -> event.paymentReference().raw()),
                new QualifiedName(PaymentCancelled.class),
                new ExtractionSequencingPolicy<>(PaymentCancelled.class, event -> event.paymentReference().raw())
        ));
    }
}
