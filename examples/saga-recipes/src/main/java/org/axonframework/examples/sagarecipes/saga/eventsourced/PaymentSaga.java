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

package org.axonframework.examples.sagarecipes.saga.eventsourced;

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
 * The rental payment process, event-sourced from events of its own.
 * <p>
 * An Axon Framework 4 saga was state-sourced whether you wanted it or not: the framework serialized the instance into
 * a saga store. Version 5 takes that decision back, and one of the options it opens up is for the process to be
 * event-sourced like anything else, from facts it writes itself.
 * <p>
 * <b>Why bother, given the derived-state recipe exists.</b> That one rebuilds its answer from the two contexts'
 * events, which requires them to be in your store and to carry a tag you can select on. Against a payment provider
 * whose events are not yours, that is impossible. This process reads only its own events, so it needs nothing from
 * the other side beyond being told that something happened. It also leaves an audit trail of the process itself,
 * which neither of the other two recipes can produce.
 * <p>
 * <b>Why the extra command.</b> Recording goes through {@link RecordPaymentRequested} rather than being appended
 * here, so that every event still comes from a command and the process's own write stays visible on an event model
 * as a write slice. The price is a second dispatch per step, and {@code saga.eventsourcedappend} is the same recipe
 * without it.
 * <p>
 * <b>The ordering rule is unchanged.</b> Dispatch the real work first, record only once it succeeded. Only the medium
 * differs from the repository recipe: an event instead of a row.
 * <p>
 * <b>How it ends.</b> {@link RentalPaymentProcessCompleted} is appended, and every handler short-circuits on it
 * afterwards. This is what {@code @EndSaga} used to do, expressed as a fact rather than a callback.
 *
 * @author Axon Framework
 * @since 5.4.0
 */
@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "eventsourced")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class PaymentSaga {

    /**
     * Asks for payment as soon as a bike is requested, and only then records that it did.
     *
     * @param event      the event that started this process
     * @param state      the process so far, or {@code null} if it has recorded nothing yet
     * @param dispatcher dispatches the resulting commands
     * @return completes when both dispatched commands have been handled
     */
    @EventHandler
    public CompletableFuture<?> on(
            BikeRequested event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        if (state != null) {
            return CompletableFuture.completedFuture(null);
        }
        var reference = RentalPaymentReference.forRental(event.rentalId());
        return dispatcher.send(new PreparePayment(reference, RentalPricing.PRICE))
                         .getResultMessage()
                         .thenCompose(ignored -> dispatcher.send(new RecordPaymentRequested(event.rentalId(),
                                                                                            event.bikeId(),
                                                                                            event.renter(),
                                                                                            RentalPricing.PRICE))
                                                           .getResultMessage());
    }

    /**
     * Hands over the bike once the payment is in.
     *
     * @param event      the payment that came in
     * @param state      the process so far
     * @param dispatcher dispatches the resulting commands
     * @return completes when both dispatched commands have been handled
     */
    @EventHandler
    public CompletableFuture<?> on(
            PaymentConfirmed event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        if (state == null || state.completed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new ApproveRequest(state.bikeId, state.renter))
                         .getResultMessage()
                         .thenCompose(ignored -> complete(state, Outcome.APPROVED, dispatcher));
    }

    /**
     * Releases the bike when the payment is refused.
     *
     * @param event      the refusal
     * @param state      the process so far
     * @param dispatcher dispatches the resulting commands
     * @return completes when both dispatched commands have been handled
     */
    @EventHandler
    public CompletableFuture<?> on(
            PaymentRejected event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        return rejectRequest(state, dispatcher);
    }

    /**
     * Releases the bike when the payment is called off, which is how a timeout arrives here.
     *
     * @param event      the cancellation
     * @param state      the process so far
     * @param dispatcher dispatches the resulting commands
     * @return completes when both dispatched commands have been handled
     */
    @EventHandler
    public CompletableFuture<?> on(
            PaymentCancelled event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        return rejectRequest(state, dispatcher);
    }

    /**
     * Calls off the payment when the request is turned down for reasons of its own.
     *
     * @param event      the rejection
     * @param state      the process so far
     * @param dispatcher dispatches the resulting commands
     * @return completes when both dispatched commands have been handled
     */
    @EventHandler
    public CompletableFuture<?> on(
            RequestRejected event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        if (state == null || state.completed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId())))
                         .getResultMessage()
                         .thenCompose(ignored -> complete(state, Outcome.REJECTED, dispatcher));
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

    /**
     * Writes down that payment was asked for. The only writer of that fact.
     *
     * @param command  the record to write
     * @param state    the process so far
     * @param appender appends the resulting event
     */
    @CommandHandler
    public void handle(RecordPaymentRequested command, @InjectEntity @Nullable State state, EventAppender appender) {
        if (state != null) {
            return;
        }
        appender.append(new RentalPaymentRequested(command.rentalId(),
                                                   command.bikeId(),
                                                   command.renter(),
                                                   command.amount()));
    }

    /**
     * Writes down that the process finished. The only writer of that fact.
     *
     * @param command  the record to write
     * @param state    the process so far
     * @param appender appends the resulting event
     */
    @CommandHandler
    public void handle(RecordProcessCompleted command, @InjectEntity @Nullable State state, EventAppender appender) {
        if (state == null || state.completed) {
            return;
        }
        appender.append(new RentalPaymentProcessCompleted(command.rentalId(), command.outcome()));
    }

    private CompletableFuture<?> rejectRequest(@Nullable State state, CommandDispatcher dispatcher) {
        if (state == null || state.completed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new RejectRequest(state.bikeId, state.renter))
                         .getResultMessage()
                         .thenCompose(ignored -> complete(state, Outcome.REJECTED, dispatcher));
    }

    private CompletableFuture<?> complete(State state, Outcome outcome, CommandDispatcher dispatcher) {
        return dispatcher.send(new RecordProcessCompleted(state.rentalId, outcome))
                         .getResultMessage();
    }

    /**
     * The process, sourced from its own events and nothing else.
     * <p>
     * Compare the derived-state recipe, whose criteria spans both contexts. This one selects only what the process
     * itself wrote, which is exactly why it works when the other side's events are out of reach.
     */
    // Conditional in its own right: a nested class is not covered by the condition on its outer class, so
    // without this every recipe's entity would be registered at once and Spring would reject the second
    // one for deriving the same bean name.
    @ConditionalOnProperty(name = "saga.recipe", havingValue = "eventsourced")
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
