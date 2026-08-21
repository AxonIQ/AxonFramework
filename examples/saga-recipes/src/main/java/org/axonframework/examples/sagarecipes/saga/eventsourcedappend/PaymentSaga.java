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

package org.axonframework.examples.sagarecipes.saga.eventsourcedappend;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.payment.write.cancelpayment.CancelPayment;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentProcessCompleted;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentProcessCompleted.Outcome;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentRequested;
import org.axonframework.examples.sagarecipes.saga.shared.CancelRentalPayment;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentIdResolver;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPricing;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * The same event-sourced process, recording its facts by appending them directly.
 * <p>
 * Identical decision model and identical observable behaviour to {@code saga.eventsourced}; only the recording
 * mechanism differs. The contract test runs against both, which is what turns "these are equivalent" into something
 * the build checks rather than a claim in a comment.
 * <p>
 * <b>This is supported.</b> {@code EventAppender} appears in the reference guide's table of parameters available to
 * event handlers, and the parameter resolver behind it looks only at the parameter type, never at the kind of
 * handler. It needs nothing pre-attached to the processing context: the event store transaction is created lazily on
 * first use, and a pooled processor's batch context is an ordinary unit of work.
 * <p>
 * <b>What it buys.</b> Half the code, and the recording becomes atomic with the tracking token, because the append
 * joins the same event store transaction the work package commits alongside the token. Neither the repository recipe
 * nor the command-translating one can manage that. Note how little that is worth here, though: because
 * {@code PreparePayment} is idempotent, the window the other recipes leave open costs nothing, since a retry
 * re-dispatches it as a no-op and then records. Atomic recording earns its keep only when the target command cannot
 * be made idempotent, and at that point there is a bigger problem to solve.
 * <p>
 * <b>What it costs.</b> An event now appears without a command behind it, which is what Event Modelling asks you not
 * to do: the process's own write stops being a write slice on the model. The append condition is also batch-wide,
 * since everything sourced across the batch is combined into one, so the conflict surface is wider than the
 * per-command condition of the command-translating variant, and a single conflict fails the whole batch.
 * <p>
 * <b>The trap to avoid.</b> Appending is safe here only because {@link InjectEntity} sourced something first. A
 * handler that appends without having sourced anything gets an unconditional append condition and therefore no
 * optimistic concurrency at all, silently.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "eventsourced-append")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class PaymentSaga {

    /**
     * Asks for payment as soon as a bike is requested, and only then records that it did.
     *
     * @param event      the event that started this process
     * @param state      the process so far, or {@code null} if it has recorded nothing yet
     * @param dispatcher dispatches the resulting command
     * @param appender   appends the process's own event
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(
            BikeRequested event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher,
            EventAppender appender
    ) {
        if (state != null) {
            return CompletableFuture.completedFuture(null);
        }
        var reference = RentalPaymentReference.forRental(event.rentalId());
        // Same ordering rule as every other recipe: do the work, then record it. Only the medium changed.
        return dispatcher.send(new PreparePayment(reference, RentalPricing.PRICE))
                         .getResultMessage()
                         .thenRun(() -> appender.append(new RentalPaymentRequested(event.rentalId(),
                                                                                   event.bikeId(),
                                                                                   event.renter(),
                                                                                   RentalPricing.PRICE)));
    }

    /**
     * Hands over the bike once the payment is in.
     *
     * @param event      the payment that came in
     * @param state      the process so far
     * @param dispatcher dispatches the resulting command
     * @param appender   appends the process's own event
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(
            PaymentConfirmed event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher,
            EventAppender appender
    ) {
        if (state == null || state.completed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new ApproveRequest(state.bikeId, state.renter))
                         .getResultMessage()
                         .thenRun(() -> complete(state, Outcome.APPROVED, appender));
    }

    /**
     * Releases the bike when the payment is refused.
     *
     * @param event      the refusal
     * @param state      the process so far
     * @param dispatcher dispatches the resulting command
     * @param appender   appends the process's own event
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(
            PaymentRejected event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher,
            EventAppender appender
    ) {
        return rejectRequest(state, dispatcher, appender);
    }

    /**
     * Releases the bike when the payment is called off, which is how a timeout arrives here.
     *
     * @param event      the cancellation
     * @param state      the process so far
     * @param dispatcher dispatches the resulting command
     * @param appender   appends the process's own event
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(
            PaymentCancelled event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher,
            EventAppender appender
    ) {
        return rejectRequest(state, dispatcher, appender);
    }

    /**
     * Calls off the payment when the request is turned down for reasons of its own.
     *
     * @param event      the rejection
     * @param state      the process so far
     * @param dispatcher dispatches the resulting command
     * @param appender   appends the process's own event
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(
            RequestRejected event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher,
            EventAppender appender
    ) {
        if (state == null || state.completed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId())))
                         .getResultMessage()
                         .thenRun(() -> complete(state, Outcome.REJECTED, appender));
    }

    /**
     * Gives up waiting for payment, unless the process already finished.
     *
     * @param command    the request to give up
     * @param state      the process so far
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @CommandHandler
    public CompletableFuture<?> handle(
            CancelRentalPayment command,
            @InjectEntity @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        if (state == null || state.completed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(command.rentalId())))
                         .getResultMessage();
    }

    private CompletableFuture<?> rejectRequest(
            @Nullable State state,
            CommandDispatcher dispatcher,
            EventAppender appender
    ) {
        if (state == null || state.completed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new RejectRequest(state.bikeId, state.renter))
                         .getResultMessage()
                         .thenRun(() -> complete(state, Outcome.REJECTED, appender));
    }

    private void complete(State state, Outcome outcome, EventAppender appender) {
        appender.append(new RentalPaymentProcessCompleted(state.rentalId, outcome));
    }

    /**
     * The process, sourced from its own events. Identical to the command-translating variant's model, and sourcing
     * through it is also what gives the appends above a real append condition.
     */
    @ConditionalOnProperty(name = "saga.recipe", havingValue = "eventsourced-append")
    @EventSourced(idType = RentalId.class)
    static class State {

        private final RentalId rentalId;
        private final BikeId bikeId;
        private final String renter;
        private boolean completed;

        @EntityCreator
        State(RentalPaymentRequested event) {
            this.rentalId = event.rentalId();
            this.bikeId = event.bikeId();
            this.renter = event.renter();
        }

        @EventSourcingHandler
        void evolve(RentalPaymentProcessCompleted event) {
            this.completed = true;
        }

        /**
         * Selects this process's own events, and only those.
         *
         * @param rentalId the rental this process concerns
         * @return the criteria selecting exactly the events this process wrote
         */
        @EventCriteriaBuilder
        private static EventCriteria criteria(RentalId rentalId) {
            return EventCriteria.havingTags(Tag.of(RentalTags.RENTAL_ID, rentalId.raw()))
                                .andBeingOneOfTypes(RentalPaymentRequested.class.getName(),
                                                    RentalPaymentProcessCompleted.class.getName());
        }
    }
}
