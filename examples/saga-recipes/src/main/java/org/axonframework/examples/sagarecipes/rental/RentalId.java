package org.axonframework.examples.sagarecipes.rental;

import java.util.UUID;

public record RentalId(String raw) {
    public RentalId {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Rental ID cannot be blank");
        }
    }

    public static RentalId of(String raw) {
        return new RentalId(raw);
    }

    public static RentalId random() {
        return new RentalId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return raw;
    }
}
