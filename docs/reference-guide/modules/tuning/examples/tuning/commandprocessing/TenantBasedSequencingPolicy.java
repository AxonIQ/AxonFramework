package tuning.commandprocessing;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.Optional;

// tag::custom-policy[]
public class TenantBasedSequencingPolicy implements SequencingPolicy<CommandMessage> {

    @Override
    public Optional<Object> sequenceIdentifierFor(CommandMessage command, ProcessingContext context) {
        return Optional.ofNullable(command.metadata().get("tenantId")).map(Object.class::cast);
    }
}
// end::custom-policy[]
