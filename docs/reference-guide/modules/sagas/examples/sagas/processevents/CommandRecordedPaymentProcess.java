package sagas.processevents;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.annotation.InjectEntity;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

class CommandRecordedPaymentProcess {
    private static final BigDecimal PRICE = new BigDecimal("10.00");

    // tag::record-process-step[]
    @EventHandler
    CompletableFuture<?> on(BikeRequested event,
                            CommandDispatcher dispatcher,
                            ProcessingContext context) {
        return load(event.rentalId(), context).thenCompose(state -> {
            if (state != null && (state.paymentRequested || state.completed)) {
                return CompletableFuture.completedFuture(null);
            }
            return dispatcher.send(
                    new PreparePayment(event.rentalId(), PRICE), Object.class
            ).thenCompose(ignored -> dispatcher.send(
                    new RecordPaymentRequested(
                            event.rentalId(), event.bikeId(), event.renter(), PRICE
                    ),
                    Object.class
            ));
        });
    }
    // end::record-process-step[]

    // tag::record-command-handler[]
    @CommandHandler
    void handle(RecordPaymentRequested command,
                @Nullable @InjectEntity ProcessState state,
                EventAppender appender) {
        if (state == null || (!state.paymentRequested && !state.completed)) {
            appender.append(new RentalPaymentRequested(
                    command.rentalId(), command.bikeId(), command.renter(), command.amount()
            ));
        }
    }
    // end::record-command-handler[]

    private CompletableFuture<@Nullable ProcessState> load(RentalId id, ProcessingContext context) {
        return context.component(StateManager.class).loadEntity(ProcessState.class, id, context);
    }

    // tag::process-event-state[]
    @EventSourced(tagKey = "rentalId", idType = RentalId.class)
    static class ProcessState {
        private BikeId bikeId;
        private String renter;
        private boolean paymentRequested;
        private boolean completed;

        @EntityCreator
        ProcessState() {
        }

        @EventSourcingHandler
        void evolve(RentalPaymentRequested event) {
            bikeId = event.bikeId();
            renter = event.renter();
            paymentRequested = true;
        }

        @EventSourcingHandler
        void evolve(RentalPaymentProcessCompleted event) {
            completed = true;
        }
    }
    // end::process-event-state[]
}

record RentalId(String value) {
}

record BikeId(String value) {
}

record BikeRequested(BikeId bikeId, String renter, RentalId rentalId) {
}

record PreparePayment(RentalId paymentReference, BigDecimal amount) {
}

record RecordPaymentRequested(@TargetEntityId RentalId rentalId, BikeId bikeId, String renter, BigDecimal amount) {
}

record RentalPaymentRequested(@EventTag(key = "rentalId") RentalId rentalId,
                              BikeId bikeId,
                              String renter,
                              BigDecimal amount) {
}

record RentalPaymentProcessCompleted(@EventTag(key = "rentalId") RentalId rentalId, String outcome) {
}
