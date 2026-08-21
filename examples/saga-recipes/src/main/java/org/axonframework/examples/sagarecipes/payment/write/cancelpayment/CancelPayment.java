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

package org.axonframework.examples.sagarecipes.payment.write.cancelpayment;

import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Calls off a payment that nobody has paid yet.
 * <p>
 * Targets the caller's reference rather than the payment identifier, because it comes from whoever ordered the
 * payment, and that party knows the key it chose. This is the improvement over Axon Framework 4, where the
 * equivalent command took a payment identifier and the saga therefore had to remember one.
 *
 * @param paymentReference the caller's own key for the payment to call off
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public record CancelPayment(@TargetEntityId PaymentReference paymentReference) {

}
