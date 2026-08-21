package org.axonframework.examples.sagarecipes.payment;

import java.util.UUID;

public record PaymentId(String raw) {
    public PaymentId {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Payment ID cannot be blank");
        }
    }

    public static PaymentId of(String raw) {
        return new PaymentId(raw);
    }

    public static PaymentId random() {
        return new PaymentId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return raw;
    }
}
