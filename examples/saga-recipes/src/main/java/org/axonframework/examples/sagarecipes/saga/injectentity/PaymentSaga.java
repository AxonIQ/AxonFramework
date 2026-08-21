package org.axonframework.examples.sagarecipes.saga.injectentity;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.ForcedEntityCreator;
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
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

import static org.axonframework.examples.sagarecipes.saga.shared.SagaConstants.PRICE;

@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "injectentity")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
public class PaymentSaga {
    @EventHandler
    CompletableFuture<?> on(BikeRequested event, CommandDispatcher dispatcher, ProcessingContext context) {
        return load(event.rentalId(), context).thenCompose(state -> state.paymentRequested
                ? CompletableFuture.completedFuture(null)
                : dispatcher.send(new PreparePayment(RentalPaymentReference.forRental(event.rentalId()), PRICE),
                                  Object.class));
    }

    @EventHandler
    CompletableFuture<?> on(PaymentConfirmed event, CommandDispatcher dispatcher, ProcessingContext context) {
        return load(RentalPaymentReference.toRental(event.paymentReference()), context).thenCompose(state ->
                state.requestSettled
                        ? CompletableFuture.completedFuture(null)
                        : dispatcher.send(new ApproveRequest(state.bikeId, state.renter), Object.class));
    }

    @EventHandler
    CompletableFuture<?> on(PaymentRejected event, CommandDispatcher dispatcher, ProcessingContext context) {
        return reject(RentalPaymentReference.toRental(event.paymentReference()), dispatcher, context);
    }

    @EventHandler
    CompletableFuture<?> on(PaymentCancelled event, CommandDispatcher dispatcher, ProcessingContext context) {
        return reject(RentalPaymentReference.toRental(event.paymentReference()), dispatcher, context);
    }

    @EventHandler
    CompletableFuture<?> on(RequestRejected event, CommandDispatcher dispatcher) {
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(event.rentalId()),
                                                 "rental request rejected"), Object.class);
    }

    @CommandHandler
    CompletableFuture<?> handle(CancelRentalPayment command, @InjectEntity InjectEntityState state,
                                CommandDispatcher dispatcher) {
        if (state.paymentConfirmed) {
            return CompletableFuture.completedFuture(null);
        }
        return dispatcher.send(new CancelPayment(RentalPaymentReference.forRental(command.rentalId()),
                                                 "not confirmed in time"), Object.class);
    }

    private CompletableFuture<?> reject(RentalId id, CommandDispatcher dispatcher, ProcessingContext context) {
        return load(id, context).thenCompose(state -> state.requestSettled
                ? CompletableFuture.completedFuture(null)
                : dispatcher.send(new RejectRequest(state.bikeId, state.renter), Object.class));
    }

    private CompletableFuture<InjectEntityState> load(RentalId id, ProcessingContext context) {
        return context.component(StateManager.class).loadEntity(InjectEntityState.class, id, context)
                      .thenApply(state -> state == null ? new InjectEntityState() : state);
    }

    @EventSourced(idType = RentalId.class)
    static class InjectEntityState {
        private BikeId bikeId;
        private String renter;
        private boolean paymentRequested;
        private boolean paymentConfirmed;
        private boolean requestSettled;

        @ForcedEntityCreator InjectEntityState() { }
        @EventSourcingHandler void evolve(BikeRequested event) { bikeId = event.bikeId(); renter = event.renter(); }
        @EventSourcingHandler void evolve(PaymentPrepared event) { paymentRequested = true; }
        @EventSourcingHandler void evolve(PaymentConfirmed event) { paymentConfirmed = true; }
        @EventSourcingHandler void evolve(PaymentRejected event) { }
        @EventSourcingHandler void evolve(PaymentCancelled event) { }
        @EventSourcingHandler void evolve(BikeInUse event) { requestSettled = true; }
        @EventSourcingHandler void evolve(RequestRejected event) { requestSettled = true; }

        @EventCriteriaBuilder
        static EventCriteria criteria(RentalId id) {
            return EventCriteria.either(
                    EventCriteria.havingTags(Tag.of(RentalTags.RENTAL_ID, id.raw()))
                                 .andBeingOneOfTypes(BikeRequested.class.getName(), BikeInUse.class.getName(),
                                                    RequestRejected.class.getName()),
                    EventCriteria.havingTags(Tag.of(PaymentTags.PAYMENT_REFERENCE,
                                                    RentalPaymentReference.forRental(id).raw()))
                                 .andBeingOneOfTypes(PaymentPrepared.class.getName(), PaymentConfirmed.class.getName(),
                                                    PaymentRejected.class.getName(), PaymentCancelled.class.getName())
            );
        }
    }
}
