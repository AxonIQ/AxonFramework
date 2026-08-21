package org.axonframework.examples.sagarecipes.payment.write.confirmpayment;

import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.modelling.annotation.TargetEntityId;

public record ConfirmPayment(@TargetEntityId PaymentId paymentId) {
}
