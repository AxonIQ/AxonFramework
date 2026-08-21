package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.examples.sagarecipes.payment.Amount;

import java.time.Duration;

public final class SagaConstants {
    public static final Amount PRICE = Amount.euros(10);
    public static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(15);

    private SagaConstants() {
    }
}
