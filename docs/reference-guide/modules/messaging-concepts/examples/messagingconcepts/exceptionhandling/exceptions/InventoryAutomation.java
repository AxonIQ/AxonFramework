package messagingconcepts.exceptionhandling.exceptions;

import messagingconcepts.exceptionhandling.DatabaseUnavailableException;
import messagingconcepts.exceptionhandling.Inventory;
import messagingconcepts.exceptionhandling.Order;
import messagingconcepts.exceptionhandling.OrderRepository;
import messagingconcepts.exceptionhandling.PlaceOrderCommand;

// tag::infrastructure-exception[]
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.modelling.annotation.InjectEntity;

class InventoryAutomation {

    // Appropriate: Exception for infrastructure failure
    @CommandHandler
    public void handle(PlaceOrderCommand command,
                       @InjectEntity Inventory inventory) {
        try {
            orderRepository.save(new Order(command.order()));
        } catch (DatabaseUnavailableException e) {
            // Truly exceptional - database is down
            throw new CommandExecutionException(
                    "Unable to process order due to system unavailability",
                    e
            );
        }
    }
    // end::infrastructure-exception[]

    private final OrderRepository orderRepository = new OrderRepository();
    // tag::infrastructure-exception[]
}
// end::infrastructure-exception[]
