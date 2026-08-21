package org.axonframework.examples.sagarecipes.saga.eventsourced;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.ForcedEntityCreator;
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
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.Outcome;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentProcessCompleted;
import org.axonframework.examples.sagarecipes.saga.eventsourced.event.RentalPaymentRequested;
import org.axonframework.examples.sagarecipes.saga.shared.CancelRentalPayment;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

import static org.axonframework.examples.sagarecipes.saga.shared.SagaConstants.PRICE;

@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "eventsourced")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class PaymentSaga {
    @EventHandler
    CompletableFuture<?> on(BikeRequested event, CommandDispatcher dispatcher, ProcessingContext context) {
        return load(event.rentalId(), context).thenCompose(state -> {
            if (state.paymentRequested || state.completed) {
                return CompletableFuture.completedFuture(null);
            }
            return dispatcher.send(new PreparePayment(RentalPaymentReference.forRental(event.rentalId()), PRICE),
                                   Object.class)
                             .thenCompose(ignored -> dispatcher.send(new RecordPaymentRequested(
                                     event.rentalId(), event.bikeId(), event.renter(), PRICE), Object.class));
        });
    }

    @EventHandler
    CompletableFuture<?> on(PaymentConfirmed event, CommandDispatcher dispatcher, ProcessingContext context) {
        RentalId id = RentalPaymentReference.toRental(event.paymentReference());
        return complete(id, Outcome.APPROVED, true, dispatcher, context);
    }

    @EventHandler
    CompletableFuture<?> on(PaymentRejected event, CommandDispatcher dispatcher, ProcessingContext context) {
        RentalId id = RentalPaymentReference.toRental(event.paymentReference());
        return complete(id, Outcome.REJECTED, false, dispatcher, context);
    }

    @EventHandler
    CompletableFuture<?> on(PaymentCancelled event, CommandDispatcher dispatcher, ProcessingContext context) {
        RentalId id = RentalPaymentReference.toRental(event.paymentReference());
        return complete(id, Outcome.CANCELLED, false, dispatcher, context);
    }

    @EventHandler
    CompletableFuture<?> on(RequestRejected event, CommandDispatcher dispatcher, ProcessingContext context) {
        return load(event.rentalId(), context).thenCompose(state -> state.completed
                ? CompletableFuture.completedFuture(null)
                : dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId()),
                                                    "rental request rejected"), Object.class)
                            .thenCompose(ignored -> dispatcher.send(
                                    new RecordProcessCompleted(event.rentalId(), Outcome.REJECTED), Object.class)));
    }

    @CommandHandler
    CompletableFuture<?> handle(CancelRentalPayment command, @InjectEntity CommandRecordedState state,
                                CommandDispatcher dispatcher) {
        if (state.completed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(command.rentalId()),
                                                 "not confirmed in time"), Object.class);
    }

    @CommandHandler
    void handle(RecordPaymentRequested command, @InjectEntity CommandRecordedState state, EventAppender appender) {
        if (!state.paymentRequested && !state.completed) {
            appender.append(new RentalPaymentRequested(command.rentalId(), command.bikeId(), command.renter(),
                                                       command.amount()));
        }
    }

    @CommandHandler
    void handle(RecordProcessCompleted command, @InjectEntity CommandRecordedState state, EventAppender appender) {
        if (!state.completed) {
            appender.append(new RentalPaymentProcessCompleted(command.rentalId(), command.outcome()));
        }
    }

    private CompletableFuture<?> complete(RentalId id, Outcome outcome, boolean approve,
                                          CommandDispatcher dispatcher, ProcessingContext context) {
        return load(id, context).thenCompose(state -> {
            if (state.completed) {
                return CompletableFuture.completedFuture(null);
            }
            Object target = approve
                    ? new ApproveRequest(state.bikeId, state.renter)
                    : new RejectRequest(state.bikeId, state.renter);
            return dispatcher.send(target, Object.class)
                             .thenCompose(ignored -> dispatcher.send(new RecordProcessCompleted(id, outcome),
                                                                    Object.class));
        });
    }

    private CompletableFuture<CommandRecordedState> load(RentalId id, ProcessingContext context) {
        return context.component(StateManager.class).loadEntity(CommandRecordedState.class, id, context)
                      .thenApply(state -> state == null ? new CommandRecordedState() : state);
    }

    @EventSourced(tagKey = RentalTags.RENTAL_ID, idType = RentalId.class)
    static class CommandRecordedState {
        private BikeId bikeId;
        private String renter;
        private boolean paymentRequested;
        private boolean completed;
        @ForcedEntityCreator CommandRecordedState() { }
        @EventSourcingHandler void evolve(RentalPaymentRequested event) {
            bikeId = event.bikeId(); renter = event.renter(); paymentRequested = true;
        }
        @EventSourcingHandler void evolve(RentalPaymentProcessCompleted event) { completed = true; }
    }
}
