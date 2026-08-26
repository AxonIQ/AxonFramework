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

import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.saga.shared.CancelRentalPayment;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Gives up on payments nobody paid, which is how a deadline is expressed without a deadline manager.
 * <p>
 * Rather than scheduling a callback into the saga's own future, the timeout becomes a projection plus a schedule: a to-do list of outstanding payments, and something
 * that periodically asks which of them have waited too long.
 * <p>
 * This is deliberately <b>not</b> part of any recipe. It is a projection over payment events that dispatches
 * {@link CancelRentalPayment}, which every recipe handles, so it applies to all of them equally. That also means the
 * timeout scenario belongs in the shared contract rather than being tested against one implementation.
 * <p>
 * <b>The design that makes it safe.</b> Writes come only from events; the schedule only reads. Rows appear when a
 * payment is prepared and disappear when it settles, so the list cannot drift from the event stream, and the sweeper
 * has no state of its own to keep in step with anything. That is precisely the hazard the repository recipe has to
 * work around, avoided by construction rather than by care.
 * <p>
 * <b>Why the work is a separate method.</b> {@code AxonTestFixture} cannot manipulate time, so a timeout driven
 * purely by a schedule would be untestable without sleeping. Taking "now" as an argument makes the interesting part
 * an ordinary method call, and leaves the annotation as one line of glue that is not worth testing.
 *
 * <h2>Where this pattern gets harder</h2>
 * The naive version looks simpler than it is, so the caveats are worth stating rather than discovering:
 * <ul>
 *     <li><b>Every instance sweeps.</b> {@code @Scheduled} runs on all of them, so a cluster of three dispatches
 *     each cancellation three times. Harmless here, because cancelling is idempotent, but wasteful; production
 *     deployments want a lock or a leader.</li>
 *     <li><b>A replay rebuilds the list with the original timestamps.</b> Every historical pending payment briefly
 *     looks overdue and gets swept. This is survivable only because the payment context ignores a cancellation of a
 *     payment that already settled. The pattern leans on rule two of this module rather harder than it first
 *     appears.</li>
 *     <li><b>Precision is the poll interval.</b> Fine for "give up after fifteen minutes", useless for "act at
 *     09:00:00 exactly".</li>
 *     <li><b>The query must stay a query.</b> Filtering at the database on an indexed column, never loading
 *     everything and filtering in memory.</li>
 * </ul>
 * The honest summary: this replaces a deadline manager with a deadline projection. Simple, replayable and needing no
 * new infrastructure, but approximate in time and duplicated across instances, and acceptable only because every
 * command it sends can be sent twice.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
@SequencingPolicy(type = PropertySequencingPolicy.class, parameters = "paymentReference")
public class PaymentsAwaitingConfirmation {

    private final PendingPaymentRepository pending;
    private final CommandGateway commandGateway;
    private final Duration paymentTimeout;

    PaymentsAwaitingConfirmation(
            PendingPaymentRepository pending,
            CommandGateway commandGateway,
            @Value("${saga.deadline.payment-timeout}")
            Duration paymentTimeout
    ) {
        this.pending = pending;
        this.commandGateway = commandGateway;
        this.paymentTimeout = paymentTimeout;
    }

    /**
     * Adds a payment to the list of those still waiting.
     *
     * @param event   the prepared payment
     * @param message the message it arrived in, for the moment it happened
     */
    @EventHandler
    public void on(PaymentPrepared event, EventMessage message) {
        pending.save(new PendingPayment(event.paymentReference(), message.timestamp()));
    }

    /**
     * Takes a paid payment off the list.
     *
     * @param event the confirmation
     */
    @EventHandler
    public void on(PaymentConfirmed event) {
        pending.deleteById(event.paymentReference().raw());
    }

    /**
     * Takes a refused payment off the list.
     *
     * @param event the refusal
     */
    @EventHandler
    public void on(PaymentRejected event) {
        pending.deleteById(event.paymentReference().raw());
    }

    /**
     * Takes a called-off payment off the list.
     *
     * @param event the cancellation
     */
    @EventHandler
    public void on(PaymentCancelled event) {
        pending.deleteById(event.paymentReference().raw());
    }

    /**
     * The scheduled trigger. One line of glue, deliberately holding no logic of its own.
     */
    @Scheduled(fixedDelayString = "${saga.deadline.sweep-interval}")
    public void tick() {
        cancelOverduePayments(Instant.now());
    }

    /**
     * Asks the process to give up on every payment that has waited too long.
     * <p>
     * A pure reader: it writes nothing, so re-running it is harmless and re-dispatching a cancellation that was
     * already handled is absorbed by the payment context.
     *
     * @param now the moment to judge against, passed in so this is testable without waiting
     */
    public void cancelOverduePayments(Instant now) {
        pending.findByPreparedAtBeforeOrderByPreparedAtAsc(now.minus(paymentTimeout))
               .forEach(payment -> commandGateway.sendAndWait(
                       new CancelRentalPayment(RentalPaymentReference.toRental(payment.paymentReference()))
               ));
    }
}
