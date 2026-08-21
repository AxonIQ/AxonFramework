package org.axonframework.examples.sagarecipes.payment.write.cancelpayment;

import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.modelling.annotation.TargetEntityId;

public record CancelPayment(@TargetEntityId PaymentReference paymentReference, String reason) {
}
