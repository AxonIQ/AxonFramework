package messagingconcepts.exceptionhandling.results;

import messagingconcepts.exceptionhandling.Inventory;
import messagingconcepts.exceptionhandling.OrderPlacedEvent;
import messagingconcepts.exceptionhandling.OrderResult;
import messagingconcepts.exceptionhandling.PlaceOrderCommand;

// tag::result-object[]
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

class InventoryAutomation {

    // Good: Returns a result object
    @CommandHandler
    public OrderResult placeOrder(PlaceOrderCommand command,
                                  @InjectEntity Inventory inventory,
                                  EventAppender appender) {
        if (inventory.sufficientFor(command.productIds())) {
            return OrderResult.failed("Insufficient balance");
        }
        if (!isAuthorized(command.userId())) {
            return OrderResult.failed("User not authorized");
        }
        appender.append(new OrderPlacedEvent(orderId));
        // Process order
        return OrderResult.success(orderId);
    }
    // end::result-object[]

    private String orderId;

    private boolean isAuthorized(String userId) {
        return true;
    }
    // tag::result-object[]
}
// end::result-object[]
