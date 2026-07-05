package commands.infrastructure.registration.springboot;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.springframework.stereotype.Component;

// tag::component-command-handler[]
@Component
public class OrderCommandHandler {

    @CommandHandler
    public void handle(CreateOrderCommand command) {
        // Handler implementation
    }
}
// end::component-command-handler[]
