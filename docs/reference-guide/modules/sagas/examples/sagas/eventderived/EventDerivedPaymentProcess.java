package sagas.eventderived;

import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.StateManager;

import java.util.concurrent.CompletableFuture;

class EventDerivedPaymentProcess {

    // tag::event-derived-handler[]
    @EventHandler
    CompletableFuture<?> on(PaymentConfirmed event,
                            CommandDispatcher dispatcher,
                            ProcessingContext context) {
        RentalId rentalId = toRentalId(event.paymentReference());
        return context.component(StateManager.class)
                      .loadEntity(EventDerivedState.class, rentalId, context)
                      .thenCompose(state -> {
                          if (state == null) {
                              return CompletableFuture.failedFuture(
                                      new IllegalStateException("Rental state is unavailable")
                              );
                          }
                          if (state.requestSettled) {
                              return CompletableFuture.completedFuture(null);
                          }
                          return dispatcher.send(
                                  new ApproveRequest(state.bikeId, state.renter),
                                  Object.class
                          );
                      });
    }
    // end::event-derived-handler[]

    private static RentalId toRentalId(PaymentReference reference) {
        return new RentalId(reference.value());
    }

    // tag::event-derived-state[]
    @EventSourced(idType = RentalId.class)
    static class EventDerivedState {
        private BikeId bikeId;
        private String renter;
        private boolean paymentRequested;
        private boolean paymentConfirmed;
        private boolean requestSettled;

        @EntityCreator
        EventDerivedState() {
        }

        @EventSourcingHandler
        void evolve(BikeRequested event) {
            bikeId = event.bikeId();
            renter = event.renter();
        }

        @EventSourcingHandler
        void evolve(PaymentPrepared event) {
            paymentRequested = true;
        }

        @EventSourcingHandler
        void evolve(PaymentConfirmed event) {
            paymentConfirmed = true;
        }

        @EventSourcingHandler
        void evolve(BikeInUse event) {
            requestSettled = true;
        }

        @EventSourcingHandler
        void evolve(RequestRejected event) {
            requestSettled = true;
        }

        @EventCriteriaBuilder
        static EventCriteria criteria(RentalId rentalId) {
            return EventCriteria.either(
                    EventCriteria.havingTags(Tag.of("rentalId", rentalId.value()))
                                 .andBeingOneOfTypes(
                                         BikeRequested.class.getName(),
                                         BikeInUse.class.getName(),
                                         RequestRejected.class.getName()
                                 ),
                    EventCriteria.havingTags(Tag.of("paymentReference", rentalId.value()))
                                 .andBeingOneOfTypes(
                                         PaymentPrepared.class.getName(),
                                         PaymentConfirmed.class.getName()
                                 )
            );
        }
    }
    // end::event-derived-state[]
}

record RentalId(String value) {
}

record BikeId(String value) {
}

record PaymentReference(String value) {
}

record BikeRequested(BikeId bikeId, String renter, RentalId rentalId) {
}

record BikeInUse(BikeId bikeId, String renter, RentalId rentalId) {
}

record RequestRejected(BikeId bikeId, RentalId rentalId, String renter) {
}

record PaymentPrepared(String paymentId, PaymentReference paymentReference) {
}

record PaymentConfirmed(String paymentId, PaymentReference paymentReference) {
}

record ApproveRequest(BikeId bikeId, String renter) {
}
