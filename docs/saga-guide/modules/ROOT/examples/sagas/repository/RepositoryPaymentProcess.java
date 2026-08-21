package sagas.repository;

// tag::repository-process[]
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
public class RepositoryPaymentProcess {
    private static final BigDecimal PRICE = new BigDecimal("10.00");

    private final PaymentProcessRepository repository;

    RepositoryPaymentProcess(PaymentProcessRepository repository) {
        this.repository = repository;
    }

    // tag::return-command-future[]
    @EventHandler
    CompletableFuture<?> on(BikeRequested event, CommandDispatcher dispatcher) {
        var existing = repository.find(event.rentalId()).orElse(null);
        if (existing != null && existing.paymentRequested()) {
            return CompletableFuture.completedFuture(null);
        }

        repository.save(PaymentProcessState.paymentRequested(
                event.rentalId(), event.bikeId(), event.renter()
        ));
        return dispatcher.send(
                new PreparePayment(referenceFor(event.rentalId()), PRICE),
                Object.class
        );
    }
    // end::return-command-future[]

    @EventHandler
    CompletableFuture<?> on(PaymentConfirmed event, CommandDispatcher dispatcher) {
        var state = repository.find(rentalIdFrom(event.paymentReference())).orElse(null);
        if (state == null) {
            return CompletableFuture.completedFuture(null);
        }

        repository.delete(state.rentalId());
        return dispatcher.send(
                new ApproveRequest(state.bikeId(), state.renter()),
                Object.class
        );
    }

    private static String referenceFor(String rentalId) {
        return rentalId;
    }

    private static String rentalIdFrom(String paymentReference) {
        return paymentReference;
    }
}
// end::repository-process[]

interface PaymentProcessRepository {
    Optional<PaymentProcessState> find(String rentalId);

    void save(PaymentProcessState state);

    void delete(String rentalId);
}

record PaymentProcessState(String rentalId, String bikeId, String renter, boolean paymentRequested) {
    static PaymentProcessState paymentRequested(String rentalId, String bikeId, String renter) {
        return new PaymentProcessState(rentalId, bikeId, renter, true);
    }
}

record BikeRequested(String bikeId, String renter, String rentalId) {
}

record PaymentConfirmed(String paymentId, String paymentReference) {
}

record PreparePayment(String paymentReference, BigDecimal amount) {
}

record ApproveRequest(String bikeId, String renter) {
}
