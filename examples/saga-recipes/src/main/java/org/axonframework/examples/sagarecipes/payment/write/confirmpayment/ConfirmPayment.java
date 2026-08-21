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

package org.axonframework.examples.sagarecipes.payment.write.confirmpayment;

import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.modelling.annotation.TargetEntityId;

/**
 * Records that a payment was paid.
 * <p>
 * Targets the payment identifier rather than the caller's reference, because this command comes from whoever is
 * paying, and the payment identifier is what they were handed. Keeps the Axon Framework 4 signature.
 *
 * @param paymentId the payment that was paid
 * @author Axon Framework
 * @since 5.4.0
 */
public record ConfirmPayment(@TargetEntityId PaymentId paymentId) {

}
