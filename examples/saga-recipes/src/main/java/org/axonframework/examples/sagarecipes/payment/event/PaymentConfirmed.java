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
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.PaymentTags;

/**
 * The payment was paid.
 * <p>
 * The reference travels with the event so whoever ordered the payment can recognise which of its own affairs this
 * concerns, without the payment context needing to understand any of it.
 *
 * @param paymentId        the payment that was paid
 * @param paymentReference the caller's own key, echoed but never interpreted
 * @author Axon Framework
 * @since 5.4.0
 */
public record PaymentConfirmed(
        @EventTag(key = PaymentTags.PAYMENT_ID) PaymentId paymentId,
        @EventTag(key = PaymentTags.PAYMENT_REFERENCE) PaymentReference paymentReference
) {

}
