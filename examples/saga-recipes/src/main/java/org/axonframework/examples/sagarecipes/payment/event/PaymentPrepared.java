package org.axonframework.examples.sagarecipes.payment.event;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.PaymentEvent;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.PaymentTags;

public record PaymentPrepared(@EventTag(key = PaymentTags.PAYMENT_ID) PaymentId paymentId,
                              Amount amount,
                              @EventTag(key = PaymentTags.PAYMENT_REFERENCE) PaymentReference paymentReference)
        implements PaymentEvent {
}
