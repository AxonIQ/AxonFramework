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
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The rental payment process, remembering what it needs in a table of its own.
 * <p>
 * The closest of the recipes to an Axon Framework 4 saga, and the one that has to be most careful. Two things happen
 * per step now, dispatching a command and writing a row, and no transaction spans both.
 * <p>
 * <b>The ordering rule.</b> Dispatch first, record only once the command has actually succeeded. The reverse order is
 * a real and quiet bug: if the dispatch fails after the row was written, the handler fails, the tracking token does
 * not advance, the event is redelivered, and the row now says the work is done. The process is wedged forever having
 * never asked for payment. {@code PaymentSagaTransactionalityTest} pins this.
 * <p>
 * <b>Why the write is deferred.</b> {@code runOnPrepareCommit} puts the save in the unit of work that also stores the
 * tracking token, so the two commit together. Writing directly from the callback that completes the command's future
 * would be wrong twice: that callback may run on a thread with no transaction bound to it, and it would run whether
 * or not the batch later commits.
 * <p>
 * <b>Returning the future is load-bearing.</b> It is what makes the processor await the command and leave the token
 * where it is on failure. Drop the {@code return} and this silently becomes fire-and-forget: the token advances, the
 * command is lost, and the process waits forever. That is what replaces version 4's {@code retryPayment} deadline.
 * <p>
 * <b>How it ends.</b> The row is deleted. That is safe here only because the commands this process sends are
 * idempotent: a redelivery after deletion restarts the process, re-dispatches, and the rental context declines to
 * append anything. Without that, a tombstone row would be needed instead.
 *
 * @author Axon Framework
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
     * @param context    the unit of work this event is handled in
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(BikeRequested event, CommandDispatcher dispatcher, ProcessingContext context) {
        if (find(event.rentalId()).filter(PaymentSagaState::paymentRequested).isPresent()) {
            return CompletableFuture.completedFuture(null);
        }
        var reference = RentalPaymentReference.forRental(event.rentalId());
        return dispatcher.send(new PreparePayment(reference, RentalPricing.PRICE))
                         .getResultMessage()
                         .thenRun(() -> context.runOnPrepareCommit(ignored -> repository.save(
                                 PaymentSagaState.paymentRequested(event.rentalId(),
                                                                   event.bikeId(),
                                                                   event.renter())
                         )));
    }

    /**
     * Hands over the bike once the payment is in.
     *
     * @param event      the payment that came in
     * @param dispatcher dispatches the resulting command
     * @param context    the unit of work this event is handled in
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(PaymentConfirmed event, CommandDispatcher dispatcher, ProcessingContext context) {
        var state = activeProcessFor(event.paymentReference());
        if (state.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var process = state.get();
        return dispatcher.send(new ApproveRequest(process.bikeId(), process.renter()))
                         .getResultMessage()
                         .thenRun(() -> context.runOnPrepareCommit(ignored -> finish(process)));
    }

    /**
     * Releases the bike when the payment is refused.
     *
     * @param event      the refusal
     * @param dispatcher dispatches the resulting command
     * @param context    the unit of work this event is handled in
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(PaymentRejected event, CommandDispatcher dispatcher, ProcessingContext context) {
        return rejectRequest(event.paymentReference(), dispatcher, context);
    }

    /**
     * Releases the bike when the payment is called off, which is how a timeout arrives here.
     *
     * @param event      the cancellation
     * @param dispatcher dispatches the resulting command
     * @param context    the unit of work this event is handled in
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(PaymentCancelled event, CommandDispatcher dispatcher, ProcessingContext context) {
        return rejectRequest(event.paymentReference(), dispatcher, context);
    }

    /**
     * Calls off the payment when the request is turned down for reasons of its own.
     *
     * @param event      the rejection
     * @param dispatcher dispatches the resulting command
     * @param context    the unit of work this event is handled in
     * @return completes when the dispatched command has been handled
     */
    @EventHandler
    public CompletableFuture<?> on(RequestRejected event, CommandDispatcher dispatcher, ProcessingContext context) {
        var state = find(event.rentalId()).filter(process -> !process.paymentSettled());
        if (state.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var process = state.get();
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId())))
                         .getResultMessage()
                         .thenRun(() -> context.runOnPrepareCommit(ignored -> finish(process)));
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

    private CompletableFuture<?> rejectRequest(
            PaymentReference reference,
            CommandDispatcher dispatcher,
            ProcessingContext context
    ) {
        var state = activeProcessFor(reference);
        if (state.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var process = state.get();
        return dispatcher.send(new RejectRequest(process.bikeId(), process.renter()))
                         .getResultMessage()
                         .thenRun(() -> context.runOnPrepareCommit(ignored -> finish(process)));
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
    private @Nullable Void finish(PaymentSagaState process) {
        repository.deleteById(process.rentalId().raw());
        return null;
    }
}
