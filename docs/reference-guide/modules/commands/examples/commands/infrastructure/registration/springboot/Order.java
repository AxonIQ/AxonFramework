package commands.infrastructure.registration.springboot;

import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

// tag::event-sourced-creation-handler[]
@EventSourced
public class Order {

    @CommandHandler
    public static Order handle(CreateOrderCommand command) {
        // Creation handler
        return new Order(command.orderId(), command.productId());
    }
    // end::event-sourced-creation-handler[]

    Order(String orderId, String productId) {
    }
    // tag::event-sourced-creation-handler[]
}
// end::event-sourced-creation-handler[]
