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

/**
 * Tag keys used by the payment context.
 * <p>
 * Both keys belong to this context alone. {@link #PAYMENT_REFERENCE} carries a value chosen by the caller, but the
 * key itself is the payment context's own vocabulary; this context has no idea what the value means. A saga that
 * selects on this key has to know that its own correlation identifier was passed in as the reference, which is
 * exactly the knowledge a saga is supposed to own.
 *
 * @author Axon Framework
 * @since 5.4.0
 */
public final class PaymentTags {

    /**
     * Identifies the payment itself, as minted by this context.
     */
    public static final String PAYMENT_ID = "paymentId";

    /**
     * Carries the caller's own key for this payment, stored and echoed but never interpreted.
     */
    public static final String PAYMENT_REFERENCE = "paymentReference";

    private PaymentTags() {
        // Utility class, not meant to be instantiated.
    }
}
