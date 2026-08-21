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

package org.axonframework.examples.sagarecipes.payment.event;

import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.examples.sagarecipes.payment.Amount;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.PaymentTags;

/**
 * A payment was set up and is waiting to be paid.
 * <p>
 * Carries both identifiers, and tags both, so a payment can afterwards be addressed either by the identity this
 * context minted or by the key its caller chose.
 *
 * @param paymentId        the identity this context minted for the payment
 * @param amount           how much is to be paid
 * @param paymentReference the caller's own key, echoed but never interpreted
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public record PaymentPrepared(
        @EventTag(key = PaymentTags.PAYMENT_ID) PaymentId paymentId,
        Amount amount,
        @EventTag(key = PaymentTags.PAYMENT_REFERENCE) PaymentReference paymentReference
) {

}
