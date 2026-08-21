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

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.PaymentTags;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Records that a payment was paid.
 * <p>
 * A payment settles once. Confirming one that was already paid, refused or called off appends nothing and reports
 * success, so the race between a late confirmation and a timeout cancellation has a defined winner: whichever
 * settles first.
 *
 * @author Axon Framework
 * @since 5.4.0
 */
@Component
public class ConfirmPaymentCommandHandler {

    /**
     * Confirms the payment, unless it has already settled.
     *
     * @param command  the command to handle
     * @param state    the targeted payment, or {@code null} if none was ever prepared under that identifier
     * @param appender appends the resulting event
     * @throws IllegalStateException if no payment exists for the given identifier
     */
    @CommandHandler
    public void handle(ConfirmPayment command, @InjectEntity @Nullable State state, EventAppender appender) {
        if (state == null) {
            throw new IllegalStateException("Payment does not exist");
        }
        if (state.settled) {
            return;
        }
        appender.append(new PaymentConfirmed(command.paymentId(), state.paymentReference));
    }

    /**
     * Decision model for this slice: whether the payment exists, whether it has settled, and which reference to echo
     * back on the outgoing event.
     * <p>
     * The reference is read from {@code PaymentPrepared} rather than supplied by the command, which is what keeps
     * the caller's key flowing through the whole lifecycle without this context ever interpreting it.
     */
    @EventSourced(idType = PaymentId.class, tagKey = PaymentTags.PAYMENT_ID)
    static class State {

        private PaymentReference paymentReference;
        private boolean settled;

        @EntityCreator
        State(PaymentPrepared event) {
            this.paymentReference = event.paymentReference();
        }

        @EventSourcingHandler
        void evolve(PaymentConfirmed event) {
            this.settled = true;
        }

        @EventSourcingHandler
        void evolve(PaymentRejected event) {
            this.settled = true;
        }

        @EventSourcingHandler
        void evolve(PaymentCancelled event) {
            this.settled = true;
        }
    }
}
