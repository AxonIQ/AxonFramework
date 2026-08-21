package org.axonframework.examples.sagarecipes.payment.write.rejectpayment;

import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.modelling.annotation.TargetEntityId;

public record RejectPayment(@TargetEntityId PaymentId paymentId, String reason) {
}
