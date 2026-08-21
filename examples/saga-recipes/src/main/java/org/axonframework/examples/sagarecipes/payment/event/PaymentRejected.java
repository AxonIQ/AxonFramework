package org.axonframework.examples.sagarecipes.payment.event;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.examples.sagarecipes.payment.PaymentEvent;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.PaymentTags;

public record PaymentRejected(@EventTag(key = PaymentTags.PAYMENT_ID) PaymentId paymentId,
                              @EventTag(key = PaymentTags.PAYMENT_REFERENCE) PaymentReference paymentReference,
                              String reason) implements PaymentEvent {
}
