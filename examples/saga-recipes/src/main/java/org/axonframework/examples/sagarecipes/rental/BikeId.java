/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.examples.sagarecipes.rental;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Identifies a bike.
 * <p>
 * This is the identifier every rental command targets, as in the bike rental sample application. The
 * saga has to remember it precisely because no other entity does, which is what each recipe solves differently.
 *
 * @param raw the raw string representation
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public record BikeId(String raw) {

    /**
     * Compact constructor rejecting blank identifiers.
     */
    public BikeId {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Bike ID cannot be null or empty");
        }
    }

    /**
     * Creates a bike identifier from the given {@code raw} value.
     *
     * @param raw the raw string representation
     * @return a bike identifier wrapping {@code raw}
     */
    public static BikeId of(String raw) {
        return new BikeId(raw);
    }

    /**
     * Creates a random bike identifier.
     *
     * @return a bike identifier backed by a random UUID
     */
    public static BikeId random() {
        return new BikeId(UUID.randomUUID().toString());
    }

    @Override
    public @NonNull String toString() {
        return raw;
    }
}
