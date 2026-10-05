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

package org.axonframework.examples.sagarecipes.saga.deadline;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;

import java.time.Instant;

/**
 * One outstanding payment, and when it started outstanding.
 * <p>
 * A row in the to-do list of payments still waiting to be paid. It exists only so the sweeper can find overdue ones
 * without reading the whole event stream.
 * <p>
 * The index is not decoration. The sweeper asks "which payments were prepared before this moment", and answering that
 * with a full scan would degrade quietly as the table fills up. Filtering belongs in the query, not in the caller.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Entity
@Table(
        name = "saga_recipe_pending_payment",
        indexes = @Index(name = "idx_saga_recipe_pending_payment_prepared_at", columnList = "preparedAt")
)
class PendingPayment {

    @Id
    private String paymentReference;
    private Instant preparedAt;

    /**
     * Required by JPA.
     */
    protected PendingPayment() {
    }

    PendingPayment(PaymentReference paymentReference, Instant preparedAt) {
        this.paymentReference = paymentReference.raw();
        this.preparedAt = preparedAt;
    }

    PaymentReference paymentReference() {
        return PaymentReference.of(paymentReference);
    }

    Instant preparedAt() {
        return preparedAt;
    }
}
