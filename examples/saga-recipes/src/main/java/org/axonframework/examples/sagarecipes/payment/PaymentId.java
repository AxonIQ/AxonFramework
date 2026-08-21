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

package org.axonframework.examples.sagarecipes.payment;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * The payment context's own identity for a payment.
 * <p>
 * Minted here, never supplied by the caller, exactly as a payment service provider mints its own reference. Whoever
 * is paying quotes this identifier back when confirming or rejecting.
 * <p>
 * A caller that needs to address a payment it ordered uses {@link PaymentReference} instead, which is why the saga
 * never has to remember a generated value.
 *
 * @param raw the raw string representation
 * @author Axon Framework
 * @since 5.4.0
 */
public record PaymentId(String raw) {

    /**
     * Compact constructor rejecting blank identifiers.
     */
    public PaymentId {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Payment ID cannot be null or empty");
        }
    }

    /**
     * Creates a payment identifier from the given {@code raw} value.
     *
     * @param raw the raw string representation
     * @return a payment identifier wrapping {@code raw}
     */
    public static PaymentId of(String raw) {
        return new PaymentId(raw);
    }

    /**
     * Mints a new payment identifier.
     *
     * @return a payment identifier backed by a random UUID
     */
    public static PaymentId random() {
        return new PaymentId(UUID.randomUUID().toString());
    }

    @Override
    public @NonNull String toString() {
        return raw;
    }
}
