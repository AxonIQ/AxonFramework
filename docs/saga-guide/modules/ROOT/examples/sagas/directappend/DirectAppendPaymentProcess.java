package sagas.directappend;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.StateManager;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

class DirectAppendPaymentProcess {
    private static final BigDecimal PRICE = new BigDecimal("10.00");

    // tag::direct-event-append[]
    @EventHandler
    CompletableFuture<?> on(BikeRequested event,
                            CommandDispatcher dispatcher,
                            ProcessingContext context,
                            EventAppender appender) {
        return context.component(StateManager.class)
                      .loadEntity(ProcessState.class, event.rentalId(), context)
                      .thenCompose(state -> {
                          if (state != null && (state.paymentRequested() || state.completed())) {
                              return CompletableFuture.completedFuture(null);
                          }
                          return dispatcher.send(
                                  new PreparePayment(event.rentalId(), PRICE), Object.class
                          ).thenRun(() -> appender.append(
                                  new RentalPaymentRequested(
                                          event.rentalId(), event.bikeId(), event.renter(), PRICE
                                  )
                          ));
                      });
    }
    // end::direct-event-append[]
}

record RentalId(String value) {
}

record BikeId(String value) {
}

record BikeRequested(BikeId bikeId, String renter, RentalId rentalId) {
}

record PreparePayment(RentalId paymentReference, BigDecimal amount) {
}

record RentalPaymentRequested(@EventTag(key = "rentalId") RentalId rentalId,
                              BikeId bikeId,
                              String renter,
                              BigDecimal amount) {
}

@EventSourced(tagKey = "rentalId", idType = RentalId.class)
class ProcessState {
    private boolean paymentRequested;
    private boolean completed;

    @EntityCreator
    ProcessState() {
    }

    @EventSourcingHandler
    void evolve(RentalPaymentRequested event) {
        paymentRequested = true;
    }

    boolean paymentRequested() {
        return paymentRequested;
    }

    boolean completed() {
        return completed;
    }
}
