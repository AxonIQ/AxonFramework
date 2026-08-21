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

package org.axonframework.examples.sagarecipes.payment.write.preparepayment;

import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.PaymentTags;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Sets up a payment to be paid.
 * <p>
 * The decision model is keyed by the caller's reference rather than by the payment identifier, and that is the whole
 * idempotency story. A redelivered command sources the payment that already exists for that reference, sees it, and
 * appends nothing. Two commands racing for the same reference cannot both win either, because the Dynamic
 * Consistency Boundary append condition covers exactly the events this decision read.
 * <p>
 * Compare Axon Framework 4, where the handler minted a payment identifier unconditionally: a retry there produced a
 * second payment for the same rental, and nothing in the model prevented it.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
public class PreparePaymentCommandHandler {

    /**
     * Prepares the payment, unless one already exists for this reference.
     * <p>
     * The entity is nullable because this is a create-if-missing flow: no entity means no payment has been prepared
     * for this reference yet. Without the nullable marker the framework would raise
     * {@link org.axonframework.modelling.repository.EntityNotFoundException} instead, which is the correct default
     * for handlers that need an entity to already exist.
     *
     * @param command  the command to handle
     * @param state    the payment already prepared for this reference, or {@code null} if there is none
     * @param appender appends the resulting event
     */
    @CommandHandler
    void handle(PreparePayment command, @InjectEntity @Nullable State state, EventAppender appender) {
        if (state != null) {
            return;
        }
        appender.append(new PaymentPrepared(PaymentId.random(), command.amount(), command.paymentReference()));
    }

    /**
     * Decision model for this slice. It holds no fields on purpose: the only question this slice asks is whether a
     * payment already exists for the caller's reference, and the entity's own existence answers it.
     * <p>
     * The creator takes {@code PaymentPrepared}, so the entity comes into being exactly when a payment does. Whether
     * that payment was later paid, refused or called off makes no difference here, and leaving those events out keeps
     * the conflict surface as narrow as the rule allows.
     */
    @EventSourced(idType = PaymentReference.class, tagKey = PaymentTags.PAYMENT_REFERENCE)
    private static class State {

        @EntityCreator
        State(PaymentPrepared event) {
        }
    }
}
