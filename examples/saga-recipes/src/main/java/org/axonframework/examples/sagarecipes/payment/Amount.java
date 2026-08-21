package org.axonframework.examples.sagarecipes.payment;

import java.math.BigDecimal;

public record Amount(BigDecimal value) {
    public Amount {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("Amount cannot be negative");
        }
    }

    public static Amount euros(long euros) {
        return new Amount(BigDecimal.valueOf(euros));
    }
}
