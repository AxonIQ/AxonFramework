package org.axonframework.examples.sagarecipes.saga.automations.whenpaymentconfirmedthenapproverequest;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.examples.sagarecipes.payment.event.PaymentConfirmed;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.write.approverequest.ApproveRequest;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentReference;
import org.axonframework.examples.sagarecipes.saga.shared.RentalPaymentSequencingPolicy;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.modelling.StateManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(name = "saga.recipe", havingValue = "automations")
@SequencingPolicy(type = RentalPaymentSequencingPolicy.class)
class WhenPaymentConfirmedThenApproveRequest {
    @EventHandler
    CompletableFuture<?> react(PaymentConfirmed event, CommandDispatcher dispatcher, ProcessingContext context) {
        RentalId id = RentalPaymentReference.toRental(event.paymentReference());
        return context.component(StateManager.class).loadEntity(RequestedRental.class, id, context)
                      .thenCompose(rental -> rental == null
                              ? CompletableFuture.failedFuture(new IllegalStateException(
                                      "Rental request state is not available for rental " + id))
                              : dispatcher.send(new ApproveRequest(rental.bikeId, rental.renter), Object.class));
    }

    @EventSourced(tagKey = RentalTags.RENTAL_ID, idType = RentalId.class)
    static class RequestedRental {
        private BikeId bikeId;
        private String renter;
        @EntityCreator RequestedRental() { }
        @EventSourcingHandler void evolve(BikeRequested event) { bikeId = event.bikeId(); renter = event.renter(); }
    }
}
