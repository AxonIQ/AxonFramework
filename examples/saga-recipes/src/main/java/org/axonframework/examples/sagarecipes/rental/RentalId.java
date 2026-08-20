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
 * Identifies a single rental request.
 * <p>
 * This is Axon Framework 4's {@code rentalReference} under a clearer name. In the version 4 demo it was a random
 * value minted inside the request handler and used only to correlate the payment; here it is supplied by the caller,
 * which makes {@code RequestBike} idempotent.
 * <p>
 * There is deliberately no {@code Rental} entity keyed by this identifier. The rental request is the process, and
 * the process is the saga. See the module README for why that matters.
 *
 * @param raw the raw string representation
 * @author Axon Framework
 * @since 5.4.0
 */
public record RentalId(String raw) {

    /**
     * Compact constructor rejecting blank identifiers.
     */
    public RentalId {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Rental ID cannot be null or empty");
        }
    }

    /**
     * Creates a rental identifier from the given {@code raw} value.
     *
     * @param raw the raw string representation
     * @return a rental identifier wrapping {@code raw}
     */
    public static RentalId of(String raw) {
        return new RentalId(raw);
    }

    /**
     * Creates a random rental identifier.
     *
     * @return a rental identifier backed by a random UUID
     */
    public static RentalId random() {
        return new RentalId(UUID.randomUUID().toString());
    }

    @Override
    public @NonNull String toString() {
        return raw;
    }
}
