package org.axonframework.examples.sagarecipes.saga.automations.whenpaymentrejectedthenrejectrequest;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.ForcedEntityCreator;
import org.axonframework.examples.sagarecipes.payment.event.PaymentRejected;
import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.examples.sagarecipes.rental.RentalTags;
import org.axonframework.examples.sagarecipes.rental.event.BikeRequested;
import org.axonframework.examples.sagarecipes.rental.write.rejectrequest.RejectRequest;
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
class WhenPaymentRejectedThenRejectRequest {
    @EventHandler
    CompletableFuture<?> react(PaymentRejected event, CommandDispatcher dispatcher, ProcessingContext context) {
        RentalId id = RentalPaymentReference.toRental(event.paymentReference());
        return context.component(StateManager.class).loadEntity(RequestedRental.class, id, context)
                      .thenApply(rental -> rental == null ? new RequestedRental() : rental)
                      .thenCompose(rental -> dispatcher.send(new RejectRequest(rental.bikeId, rental.renter),
                                                             Object.class));
    }

    @EventSourced(tagKey = RentalTags.RENTAL_ID, idType = RentalId.class)
    static class RequestedRental {
        private BikeId bikeId;
        private String renter;
        @ForcedEntityCreator RequestedRental() { }
        @EventSourcingHandler void evolve(BikeRequested event) { bikeId = event.bikeId(); renter = event.renter(); }
    }
}
