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

/**
 * The caller's own key for a payment, opaque to this context.
 * <p>
 * Whatever the caller puts in here is stored and echoed back on every payment event, and never interpreted. It could
 * be an invoice number, an order identifier or, in this example, a rental identifier; the payment context cannot
 * tell and must not care. This is Axon Framework 4's {@code paymentReference}, unchanged.
 * <p>
 * The decision model for preparing and cancelling a payment is keyed by this value rather than by
 * {@link PaymentId}. That makes preparing a payment idempotent by construction, since a second attempt with the same
 * reference finds the payment already there, and it lets a caller cancel a payment using only the key it chose
 * itself.
 *
 * @param raw the raw string representation
 * @author Axon Framework
 * @since 5.4.0
 */
public record PaymentReference(String raw) {

    /**
     * Compact constructor rejecting blank references.
     */
    public PaymentReference {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Payment reference cannot be null or empty");
        }
    }

    /**
     * Creates a payment reference from the given {@code raw} value.
     *
     * @param raw the raw string representation
     * @return a payment reference wrapping {@code raw}
     */
    public static PaymentReference of(String raw) {
        return new PaymentReference(raw);
    }

    @Override
    public @NonNull String toString() {
        return raw;
    }
}
