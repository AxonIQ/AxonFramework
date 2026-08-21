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
 * Calls off a payment that nobody has paid yet.
 * <p>
 * This is the deadline replacement. Axon Framework 5 has no {@code DeadlineManager}, so instead of a callback
 * scheduled inside a saga, the timeout is expressed as an ordinary command anyone may send at any time: a scheduled
 * sweep, an operator, a test, or a REST call.
 * <p>
 * Both non-cases are silent successes rather than failures, and that is what makes the timeout safe to arrive late:
 * <ul>
 *     <li>The payment was already paid, refused or called off. Nothing to do, and in particular a cancellation that
 *     loses the race against a confirmation must not undo it.</li>
 *     <li>No payment exists for this reference at all, because preparing it never got that far. The caller wanted
 *     the payment not to be outstanding, and it is not.</li>
 * </ul>
 *
 * @author Axon Framework
 * @since 5.4.0
 */
@Component
public class CancelPaymentCommandHandler {

    /**
     * Calls off the payment, unless it has already settled or never existed.
     *
     * @param command  the command to handle
     * @param state    the payment prepared for the caller's reference, or {@code null} if there is none
     * @param appender appends the resulting event
     */
    @CommandHandler
    public void handle(CancelPayment command, @InjectEntity @Nullable State state, EventAppender appender) {
        if (state == null || state.settled) {
            return;
        }
        appender.append(new PaymentCancelled(state.paymentId, command.paymentReference()));
    }

    /**
     * Decision model for this slice: which payment the caller's reference points at, and whether it has settled.
     * <p>
     * Note that this slice reads the payment identifier it never received. Keying on the reference is what lets the
     * caller address a payment whose identity it was never told.
     */
    @EventSourced(idType = PaymentReference.class, tagKey = PaymentTags.PAYMENT_REFERENCE)
    static class State {

        private PaymentId paymentId;
        private boolean settled;

        @EntityCreator
        State(PaymentPrepared event) {
            this.paymentId = event.paymentId();
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
