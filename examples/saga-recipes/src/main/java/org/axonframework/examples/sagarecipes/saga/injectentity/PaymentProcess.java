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

package org.axonframework.examples.sagarecipes.saga.injectentity;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.payment.PaymentTags;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentPrepared;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.payment.write.cancelpayment.CancelPayment;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeInUse;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
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
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * The rental payment process, holding no state of its own.
 * <p>
 * Whatever this process needs to know is already recorded somewhere, so rather than keeping a copy it rebuilds the
 * answer on demand from the events both contexts have already written. There is no saga store, no repository and no
 * process event: the {@link State} below is event-sourced on the fly, for one rental, each time a handler runs.
 * <p>
 * <b>What this buys.</b> Only one thing can go wrong per handler, because there is only one effect. Nothing is
 * written by the process itself, so the ordering hazard that recipes keeping their own state have to work around
 * simply cannot arise. If a command fails, no event is appended and the event is not recorded as handled, so it
 * arrives again, rebuilds an identical decision model, and tries again.
 * <p>
 * <b>What it costs.</b> The process is readable only through its outcomes. A step that produces no event, an e-mail
 * or an outbound call, leaves no trace here and cannot be tracked. It also requires that both contexts write to one
 * event store and that their events carry a tag this process can select on. Against a payment provider whose events
 * are not yours to source, this recipe is simply unavailable, and the event-sourced recipe applies instead.
 * <p>
 * <b>How it ends.</b> It does not, in the sense of writing anything. Being finished is a question asked of the
 * events, {@link State#requestSettled}, rather than a fact recorded anywhere. Nothing to delete, nothing to clean up.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "injectentity")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class PaymentProcess {

    /**
     * Asks for payment as soon as a bike is requested.
     *
     * @param event      the event that started this process
     * @param state      the process so far, or {@code null} if this is its first event
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    CompletableFuture<?> on(
            BikeRequested event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        if (state != null && state.paymentRequested) {
            return CompletableFuture.completedFuture(null);
        }
        var reference = RentalPaymentReference.forRental(event.rentalId());
        return dispatcher.send(new PreparePayment(reference, RentalPricing.PRICE))
                         .getResultMessage();
    }

    /**
     * Hands over the bike once the payment is in.
     *
     * @param event      the payment that came in
     * @param state      the process so far
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    CompletableFuture<?> on(
            PaymentConfirmed event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        if (state == null || state.requestSettled) {
            return CompletableFuture.completedFuture(null);
        }
        // The bike and the renter are read back from BikeRequested. This is the whole recipe in one line: the state
        // a saga would otherwise have to store is recovered from events that already exist.
        return dispatcher.send(new ApproveRequest(state.bikeId, state.renter))
                         .getResultMessage();
    }

    /**
     * Releases the bike when the payment is refused.
     *
     * @param event      the refusal
     * @param state      the process so far
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    CompletableFuture<?> on(
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
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    CompletableFuture<?> on(
            PaymentCancelled event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        return rejectRequest(state, dispatcher);
    }

    /**
     * Calls off the payment when the request is turned down for reasons of its own.
     * <p>
     * This is the compensating direction: without it a rental rejected on other grounds would leave a payment
     * outstanding forever.
     *
     * @param event      the rejection
     * @param state      the process so far
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    CompletableFuture<?> on(
            RequestRejected event,
            @InjectEntity(idResolver = RentalPaymentIdResolver.class) @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        if (state == null || state.paymentSettled) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId())))
                         .getResultMessage();
    }

    /**
     * Gives up waiting for payment, unless it already arrived.
     * <p>
     * The process checks first because it can see that giving up is pointless, but the payment context checks again,
     * and that second check is the one that holds under a race.
     *
     * @param command    the request to give up
     * @param state      the process so far
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @CommandHandler
    CompletableFuture<?> handle(
            CancelRentalPayment command,
            @InjectEntity @Nullable State state,
            CommandDispatcher dispatcher
    ) {
        if (state == null || state.paymentSettled) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(command.rentalId())))
                         .getResultMessage();
    }

    private CompletableFuture<?> rejectRequest(@Nullable State state, CommandDispatcher dispatcher) {
        if (state == null || state.requestSettled) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new RejectRequest(state.bikeId, state.renter))
                         .getResultMessage();
    }

    /**
     * The process, reconstructed from events that already exist.
     * <p>
     * Note where the two contexts meet. Rental events are selected by the {@code rentalId} tag; payment events by the
     * {@code paymentReference} tag, whose value happens to be that same rental identifier. The payment context has no
     * idea, and the rental context has no idea. Knowing it is exactly what this package is for.
     * <p>
     * The condition is repeated here on purpose. A nested class is not covered by the one on its outer class, so
     * without it every recipe's model would be registered at once, and Spring would reject the second for deriving
     * the same bean name.
     */
    @ConditionalOnProperty(name = "saga.recipe", havingValue = "injectentity")
    @EventSourced(idType = RentalId.class)
    private static class State {

        private BikeId bikeId;
        private String renter;
        private boolean paymentRequested;
        private boolean paymentSettled;
        private boolean requestSettled;

        @EntityCreator
        State(BikeRequested event) {
            this.bikeId = event.bikeId();
            this.renter = event.renter();
        }

        @EventSourcingHandler
        void evolve(PaymentPrepared event) {
            this.paymentRequested = true;
        }

        @EventSourcingHandler
        void evolve(PaymentConfirmed event) {
            this.paymentSettled = true;
        }

        @EventSourcingHandler
        void evolve(PaymentRejected event) {
            this.paymentSettled = true;
        }

        @EventSourcingHandler
        void evolve(PaymentCancelled event) {
            this.paymentSettled = true;
        }

        @EventSourcingHandler
        void evolve(BikeInUse event) {
            this.requestSettled = true;
        }

        @EventSourcingHandler
        void evolve(RequestRejected event) {
            this.requestSettled = true;
        }

        /**
         * Selects one rental's events from both contexts at once.
         *
         * @param rentalId the rental this process concerns
         * @return the criteria selecting exactly the events this process depends on
         */
        @EventCriteriaBuilder
        private static EventCriteria criteria(RentalId rentalId) {
            return EventCriteria.either(
                    EventCriteria.havingTags(Tag.of(RentalTags.RENTAL_ID, rentalId.raw()))
                                 .andBeingOneOfTypes(BikeRequested.class.getName(),
                                                     BikeInUse.class.getName(),
                                                     RequestRejected.class.getName()),
                    EventCriteria.havingTags(Tag.of(PaymentTags.PAYMENT_REFERENCE,
                                                    RentalPaymentReference.forRental(rentalId).raw()))
                                 .andBeingOneOfTypes(PaymentPrepared.class.getName(),
                                                     PaymentConfirmed.class.getName(),
                                                     PaymentRejected.class.getName(),
                                                     PaymentCancelled.class.getName())
            );
        }
    }
}
