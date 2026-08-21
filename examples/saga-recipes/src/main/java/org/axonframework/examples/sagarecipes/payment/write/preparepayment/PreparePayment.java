package org.axonframework.examples.sagarecipes.payment.write.preparepayment;

import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.modelling.annotation.TargetEntityId;

public record PreparePayment(@TargetEntityId PaymentReference paymentReference, Amount amount) {
}
