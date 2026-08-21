package org.axonframework.examples.sagarecipes.payment;

public record PaymentReference(String raw) {
    public PaymentReference {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Payment reference cannot be blank");
        }
    }

    public static PaymentReference of(String raw) {
        return new PaymentReference(raw);
    }

    @Override
    public String toString() {
        return raw;
    }
}
