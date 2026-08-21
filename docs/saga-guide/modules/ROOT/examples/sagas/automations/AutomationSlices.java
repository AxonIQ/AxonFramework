package sagas.automations;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.modelling.StateManager;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

// tag::stateless-automation[]
@Component
@Namespace("rental-payment-automations")
class WhenBikeRequestedThenPreparePayment {
    private static final BigDecimal PRICE = new BigDecimal("10.00");

    @EventHandler
    CompletableFuture<?> react(BikeRequested event, CommandDispatcher dispatcher) {
        return dispatcher.send(
                new PreparePayment(event.rentalId(), PRICE), Object.class
        );
    }
}
// end::stateless-automation[]

// tag::automation-with-lookup[]
@Component
@Namespace("rental-payment-automations")
class WhenPaymentConfirmedThenApproveRequest {

    @EventHandler
    CompletableFuture<?> react(PaymentConfirmed event,
                               CommandDispatcher dispatcher,
                               ProcessingContext context) {
        RentalId rentalId = new RentalId(event.paymentReference());
        return context.component(StateManager.class)
                      .loadEntity(RequestedRental.class, rentalId, context)
                      .thenCompose(rental -> rental == null
                              ? CompletableFuture.failedFuture(
                                      new IllegalStateException("Rental request is unavailable")
                              )
                              : dispatcher.send(
                                      new ApproveRequest(rental.bikeId, rental.renter), Object.class
                              ));
    }

    @EventSourced(tagKey = "rentalId", idType = RentalId.class)
    static class RequestedRental {
        private BikeId bikeId;
        private String renter;

        @EntityCreator
        RequestedRental() {
        }

        @EventSourcingHandler
        void evolve(BikeRequested event) {
            bikeId = event.bikeId();
            renter = event.renter();
        }
    }
}
// end::automation-with-lookup[]

record RentalId(String value) {
}

record BikeId(String value) {
}

record BikeRequested(BikeId bikeId, String renter, RentalId rentalId) {
}

record PaymentConfirmed(String paymentId, String paymentReference) {
}

record PreparePayment(RentalId paymentReference, BigDecimal amount) {
}

record ApproveRequest(BikeId bikeId, String renter) {
}
