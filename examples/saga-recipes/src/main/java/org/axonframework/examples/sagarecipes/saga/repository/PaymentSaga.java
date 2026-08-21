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

package org.axonframework.examples.sagarecipes.saga.repository;

import org.axonframework.examples.sagarecipes.payment.PaymentReference;
import org.axonframework.examples.sagarecipes.payment.event.PaymentCancelled;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.payment.write.cancelpayment.CancelPayment;
import org.axonframework.examples.sagarecipes.payment.write.preparepayment.PreparePayment;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.event.RequestRejected;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
import org.axonframework.examples.sagarecipes.saga.shared.CancelRentalPayment;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPricing;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The rental payment process, remembering what it needs in a table of its own.
 * <p>
 * The closest of the recipes to an Axon Framework 4 saga. Two things happen per step, writing a row and dispatching a
 * command, and getting them to agree is this recipe's whole difficulty.
 * <p>
 * <b>Where the transaction comes from.</b> Nowhere in this class, which is the point. When a
 * {@link org.springframework.transaction.PlatformTransactionManager} is on the classpath, Axon Framework wraps every
 * unit of work in a transaction that begins before the handler is invoked and commits alongside the tracking token.
 * The write below is an ordinary call in an ordinary method, and it lands in that transaction because the handler runs
 * inside it. Row and token commit together, or neither does. No {@code @Transactional}, no deferral, no callback.
 * <p>
 * <b>Why the write comes first.</b> Purely so it happens on the handler's own thread, where the transaction is bound.
 * Ordering carries no correctness weight here: a failed dispatch rolls the row back with the rest of the unit of work.
 * Deferring the write until after the command succeeded is what forces it out of the transaction and into a callback
 * running on whichever thread completed the command's future, and that is a step no state-storing saga needs to take.
 * <p>
 * The exception is a process that cannot know what to store until the command has answered. Version 4's saga was in
 * exactly that position, having to keep the {@code paymentId} that {@code PaymentPreparedEvent} handed back. This one
 * is not: the payment reference is derived from the rental identifier, so everything worth storing is already in hand
 * before the command is sent.
 * <p>
 * <b>Returning the future is load-bearing.</b> A {@link CommandDispatcher} hands back a future immediately and does
 * not enlist with the unit of work, so returning it is what makes the processor await the command and leave the token
 * where it is on failure. Drop the {@code return} and this silently becomes fire-and-forget: the token advances, the
 * command is lost, and the process waits forever. That is what replaces version 4's {@code retryPayment} deadline.
 * <p>
 * <b>How it ends.</b> The row is deleted. That is safe here only because the commands this process sends are
 * idempotent: a redelivery after deletion restarts the process, re-dispatches, and the rental context declines to
 * append anything. Without that, a tombstone row would be needed instead.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "repository")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class PaymentSaga {

    private final PaymentSagaStateRepository repository;

    PaymentSaga(PaymentSagaStateRepository repository) {
        this.repository = repository;
    }

    /**
     * Asks for payment as soon as a bike is requested, and only then records that it did.
     *
     * @param event      the event that started this process
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(BikeRequested event, CommandDispatcher dispatcher) {
        if (find(event.rentalId()).filter(PaymentSagaState::paymentRequested).isPresent()) {
            return CompletableFuture.completedFuture(null);
        }
        repository.save(PaymentSagaState.paymentRequested(event.rentalId(), event.bikeId(), event.renter()));
        var reference = RentalPaymentReference.forRental(event.rentalId());
        return dispatcher.send(new PreparePayment(reference, RentalPricing.PRICE))
                         .getResultMessage();
    }

    /**
     * Hands over the bike once the payment is in.
     *
     * @param event      the payment that came in
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(PaymentConfirmed event, CommandDispatcher dispatcher) {
        var state = activeProcessFor(event.paymentReference());
        if (state.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var process = state.get();
        finish(process);
        return dispatcher.send(new ApproveRequest(process.bikeId(), process.renter()))
                         .getResultMessage();
    }

    /**
     * Releases the bike when the payment is refused.
     *
     * @param event      the refusal
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(PaymentRejected event, CommandDispatcher dispatcher) {
        return rejectRequest(event.paymentReference(), dispatcher);
    }

    /**
     * Releases the bike when the payment is called off, which is how a timeout arrives here.
     *
     * @param event      the cancellation
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(PaymentCancelled event, CommandDispatcher dispatcher) {
        return rejectRequest(event.paymentReference(), dispatcher);
    }

    /**
     * Calls off the payment when the request is turned down for reasons of its own.
     *
     * @param event      the rejection
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(RequestRejected event, CommandDispatcher dispatcher) {
        var state = find(event.rentalId()).filter(process -> !process.paymentSettled());
        if (state.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var process = state.get();
        finish(process);
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId())))
                         .getResultMessage();
    }

    /**
     * Gives up waiting for payment, unless it already arrived.
     *
     * @param command    the request to give up
     * @param dispatcher dispatches the resulting command
     * @return completes when the dispatched command has been handled
     */
    @CommandHandler
    public CompletableFuture<?> handle(CancelRentalPayment command, CommandDispatcher dispatcher) {
        if (find(command.rentalId()).filter(process -> !process.paymentSettled()).isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(command.rentalId())))
                         .getResultMessage();
    }

    private CompletableFuture<?> rejectRequest(PaymentReference reference, CommandDispatcher dispatcher) {
        var state = activeProcessFor(reference);
        if (state.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var process = state.get();
        finish(process);
        return dispatcher.send(new RejectRequest(process.bikeId(), process.renter()))
                         .getResultMessage();
    }

    private Optional<PaymentSagaState> activeProcessFor(PaymentReference reference) {
        return find(RentalPaymentReference.toRental(reference)).filter(process -> !process.requestSettled());
    }

    private Optional<PaymentSagaState> find(RentalId rentalId) {
        return repository.findById(rentalId.raw());
    }

    /**
     * Ends the process by forgetting it. See the class documentation for why deleting is safe here.
     */
    private void finish(PaymentSagaState process) {
        repository.deleteById(process.rentalId().raw());
    }
}
