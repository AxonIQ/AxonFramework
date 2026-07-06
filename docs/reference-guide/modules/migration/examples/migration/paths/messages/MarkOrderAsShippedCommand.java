package migration.paths.messages;

import org.axonframework.modelling.annotation.TargetEntityId;

// tag::target-entity-id[]
public class MarkOrderAsShippedCommand {
    @TargetEntityId
    private String orderId;
}
// end::target-entity-id[]
