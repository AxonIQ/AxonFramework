package messagingconcepts.exceptionhandling.details;

import messagingconcepts.exceptionhandling.Inventory;
import messagingconcepts.exceptionhandling.PlaceOrderCommand;

// tag::exception-details[]
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

import java.util.Map;

class InventoryAutomation {

    @CommandHandler
    public void handle(PlaceOrderCommand command,
                       @InjectEntity Inventory inventory,
                       EventAppender appender) {
        if (criticalSystemError()) {
            throw new CommandExecutionException(
                    "System unavailable",
                    null,
                    // Exception details...
                    Map.of(
                            "errorCode", "SYSTEM_UNAVAILABLE",
                            "retryable", "true"
                    )
            );
        }
        // Happy path, validating the inventory and publishing an event.
    }
    // end::exception-details[]

    private boolean criticalSystemError() {
        return false;
    }
    // tag::exception-details[]
}
// end::exception-details[]
