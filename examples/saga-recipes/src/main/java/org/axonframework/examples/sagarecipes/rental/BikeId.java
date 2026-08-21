package org.axonframework.examples.sagarecipes.rental;

import java.util.UUID;

public record BikeId(String raw) {
    public BikeId {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Bike ID cannot be blank");
        }
    }

    public static BikeId of(String raw) {
        return new BikeId(raw);
    }

    public static BikeId random() {
        return new BikeId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return raw;
    }
}
