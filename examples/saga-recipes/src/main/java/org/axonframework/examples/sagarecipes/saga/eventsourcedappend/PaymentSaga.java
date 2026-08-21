package org.axonframework.examples.sagarecipes.saga.eventsourcedappend;

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
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

import static org.axonframework.examples.sagarecipes.saga.shared.SagaConstants.PRICE;

@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "eventsourced-append")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class PaymentSaga {
    @EventHandler
    CompletableFuture<?> on(BikeRequested event, CommandDispatcher dispatcher, ProcessingContext context,
                            EventAppender appender) {
        return load(event.rentalId(), context).thenCompose(state -> {
            if (state != null && (state.paymentRequested || state.completed)) {
                return CompletableFuture.completedFuture(null);
            }
            return dispatcher.send(new PreparePayment(RentalPaymentReference.forRental(event.rentalId()), PRICE),
                                   Object.class)
                             .thenRun(() -> appender.append(new RentalPaymentRequested(
                                     event.rentalId(), event.bikeId(), event.renter(), PRICE)));
        });
    }

    @EventHandler
    CompletableFuture<?> on(PaymentConfirmed event, CommandDispatcher dispatcher, ProcessingContext context,
                            EventAppender appender) {
        return complete(RentalPaymentReference.toRental(event.paymentReference()), Outcome.APPROVED, true,
                        dispatcher, context, appender);
    }

    @EventHandler
    CompletableFuture<?> on(PaymentRejected event, CommandDispatcher dispatcher, ProcessingContext context,
                            EventAppender appender) {
        return complete(RentalPaymentReference.toRental(event.paymentReference()), Outcome.REJECTED, false,
                        dispatcher, context, appender);
    }

    @EventHandler
    CompletableFuture<?> on(PaymentCancelled event, CommandDispatcher dispatcher, ProcessingContext context,
                            EventAppender appender) {
        return complete(RentalPaymentReference.toRental(event.paymentReference()), Outcome.CANCELLED, false,
                        dispatcher, context, appender);
    }

    @EventHandler
    CompletableFuture<?> on(RequestRejected event, CommandDispatcher dispatcher, ProcessingContext context,
                            EventAppender appender) {
        return load(event.rentalId(), context).thenCompose(state -> state != null && state.completed
                ? CompletableFuture.completedFuture(null)
                : dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId()),
                                                    "rental request rejected"), Object.class)
                            .thenRun(() -> appender.append(
                                    new RentalPaymentProcessCompleted(event.rentalId(), Outcome.REJECTED))));
    }

    @CommandHandler
    CompletableFuture<?> handle(CancelRentalPayment command, @Nullable @InjectEntity DirectAppendState state,
                                CommandDispatcher dispatcher) {
        if (state != null && state.completed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(command.rentalId()),
                                                 "not confirmed in time"), Object.class);
    }

    private CompletableFuture<?> complete(RentalId id, Outcome outcome, boolean approve,
                                          CommandDispatcher dispatcher, ProcessingContext context,
                                          EventAppender appender) {
        return load(id, context).thenCompose(state -> {
            if (state == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Payment process state is not available for rental " + id));
            }
            if (state.completed) {
                return CompletableFuture.completedFuture(null);
            }
            Object target = approve
                    ? new ApproveRequest(state.bikeId, state.renter)
                    : new RejectRequest(state.bikeId, state.renter);
            return dispatcher.send(target, Object.class)
                             .thenRun(() -> appender.append(new RentalPaymentProcessCompleted(id, outcome)));
        });
    }

    private CompletableFuture<@Nullable DirectAppendState> load(RentalId id, ProcessingContext context) {
        return context.component(StateManager.class).loadEntity(DirectAppendState.class, id, context);
    }

    @EventSourced(tagKey = RentalTags.RENTAL_ID, idType = RentalId.class)
    static class DirectAppendState {
        private BikeId bikeId;
        private String renter;
        private boolean paymentRequested;
        private boolean completed;
        @EntityCreator DirectAppendState() { }
        @EventSourcingHandler void evolve(RentalPaymentRequested event) {
            bikeId = event.bikeId(); renter = event.renter(); paymentRequested = true;
        }
        @EventSourcingHandler void evolve(RentalPaymentProcessCompleted event) { completed = true; }
    }
}
