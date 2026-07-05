package commands.infrastructure.repositories;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.repository.Repository;
import org.springframework.stereotype.Component;

// tag::repository-command-handler[]
@Component
public class OrderCommandHandler {

    private final Repository<String, Order> orderRepository;

    public OrderCommandHandler(Repository<String, Order> orderRepository) {
        this.orderRepository = orderRepository;
    }

    @CommandHandler
    public void handle(ShipOrderCommand command,
                      ProcessingContext context,
                      EventAppender eventAppender) {
        // Load the entity
        orderRepository.load(command.orderId(), context)
            .thenAccept(managedOrder -> {
                Order order = managedOrder.entity();
                // Apply business logic
                if (order.canShip()) {
                    eventAppender.append(new OrderShippedEvent(command.orderId()));
                }
            });
    }
}
// end::repository-command-handler[]
