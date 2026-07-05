package commands.infrastructure.gateway;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

// tag::order-controller[]
@RestController
public class OrderController {

    private final CommandGateway commandGateway;

    public OrderController(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PostMapping("/orders")
    public CompletableFuture<String> createOrder(@RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = new CreateOrderCommand(
            UUID.randomUUID().toString(),
            request.getProductId(),
            request.getQuantity()
        );

        // Returns CompletableFuture<String> with the order ID
        return commandGateway.send(command, String.class);
    }

    @PostMapping("/orders/{id}/ship")
    public void shipOrder(@PathVariable String id) {
        // Fire and forget
        commandGateway.sendAndWait(new ShipOrderCommand(id));
    }
}
// end::order-controller[]
