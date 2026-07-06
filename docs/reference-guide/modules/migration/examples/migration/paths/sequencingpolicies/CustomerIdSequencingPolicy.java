package migration.paths.sequencingpolicies;

// tag::customer-id-sequencing-policy[]
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

import java.util.Optional;

public class CustomerIdSequencingPolicy implements SequencingPolicy<EventMessage> {

    @Override
    public Optional<Object> sequenceIdentifierFor(EventMessage message, ProcessingContext context) {
        if (message.payload() instanceof CustomerEvent customerEvent) {
            return Optional.of(customerEvent.customerId());
        }
        return Optional.empty();
    }
}
// end::customer-id-sequencing-policy[]

record CustomerEvent(String customerId) {

}
