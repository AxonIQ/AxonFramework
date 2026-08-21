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

package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.rental.RentalId;

/**
 * Translates between a rental and the payment reference that stands for it.
 * <p>
 * This is the only code in the application that knows these two are the same value. The rental context does not know
 * payments exist, and the payment context treats the reference as an opaque string. Holding that one piece of
 * knowledge, and nothing else, is what the saga is for.
 * <p>
 * Because the mapping is derived rather than stored, the saga needs no state to correlate in either direction:
 * outbound it computes the reference from the rental, inbound it reads the reference off the payment event. That is
 * worth noticing before reaching for a saga at all, since a large share of Axon Framework 4 sagas kept state purely
 * to remember an identifier they could have computed.
 * <p>
 * The alternative, a randomly generated reference, would force a stored mapping and rule out the recipes that keep
 * no state. Should a rental ever need a second payment attempt, the derivation would have to include the attempt
 * number rather than being abandoned.
 *
 * @author Axon Framework
 * @since 5.4.0
 */
public final class RentalPaymentReference {

    /**
     * Returns the payment reference standing for the given rental.
     *
     * @param rentalId the rental to pay for
     * @return the reference the payment context will echo back
     */
    public static PaymentReference forRental(RentalId rentalId) {
        return PaymentReference.of(rentalId.raw());
    }

    /**
     * Returns the rental a payment reference stands for.
     *
     * @param paymentReference the reference echoed by a payment event
     * @return the rental that payment belongs to
     */
    public static RentalId toRental(PaymentReference paymentReference) {
        return RentalId.of(paymentReference.raw());
    }

    private RentalPaymentReference() {
        // Utility class, not meant to be instantiated.
    }
}
