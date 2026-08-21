package org.axonframework.examples.sagarecipes.saga.repository;

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
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

import static org.axonframework.examples.sagarecipes.saga.shared.SagaConstants.PRICE;

@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "repository")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class PaymentSaga {
    private final PaymentSagaStateRepository repository;

    public PaymentSaga(PaymentSagaStateRepository repository) {
        this.repository = repository;
    }

    @EventHandler
    CompletableFuture<?> on(BikeRequested event, CommandDispatcher dispatcher, ProcessingContext context) {
        var existing = repository.findById(event.rentalId().raw()).orElse(null);
        if (existing != null && existing.paymentRequested()) {
            return CompletableFuture.completedFuture(null);
        }
        context.runOnPrepareCommit(ignored -> repository.save(PaymentSagaState.paymentRequested(
                event.rentalId(), event.bikeId(), event.renter())));
        return dispatcher.send(new PreparePayment(RentalPaymentReference.forRental(event.rentalId()), PRICE),
                               Object.class);
    }

    @EventHandler
    CompletableFuture<?> on(PaymentConfirmed event, CommandDispatcher dispatcher, ProcessingContext context) {
        return settle(RentalPaymentReference.toRental(event.paymentReference()), true, dispatcher, context);
    }

    @EventHandler
    CompletableFuture<?> on(PaymentRejected event, CommandDispatcher dispatcher, ProcessingContext context) {
        return settle(RentalPaymentReference.toRental(event.paymentReference()), false, dispatcher, context);
    }

    @EventHandler
    CompletableFuture<?> on(PaymentCancelled event, CommandDispatcher dispatcher, ProcessingContext context) {
        return settle(RentalPaymentReference.toRental(event.paymentReference()), false, dispatcher, context);
    }

    @EventHandler
    CompletableFuture<?> on(RequestRejected event, CommandDispatcher dispatcher) {
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId()),
                                                 "rental request rejected"), Object.class);
    }

    @CommandHandler
    CompletableFuture<?> handle(CancelRentalPayment command, CommandDispatcher dispatcher) {
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(command.rentalId()),
                                                 "not confirmed in time"), Object.class);
    }

    private CompletableFuture<?> settle(RentalId rentalId, boolean approved, CommandDispatcher dispatcher,
                                        ProcessingContext context) {
        var state = repository.findById(rentalId.raw()).orElse(null);
        if (state == null) {
            return CompletableFuture.completedFuture(null);
        }
        context.runOnPrepareCommit(ignored -> repository.deleteById(rentalId.raw()));
        Object command = approved
                ? new ApproveRequest(state.bikeId(), state.renter())
                : new RejectRequest(state.bikeId(), state.renter());
        return dispatcher.send(command, Object.class);
    }
}
