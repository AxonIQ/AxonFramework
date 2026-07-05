package commands.commanddispatchers;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.RoutingStrategy;

// tag::custom-routing-strategy[]
public class CustomRoutingStrategy implements RoutingStrategy {

    @Override
    public String getRoutingKey(CommandMessage command) {
        // Custom logic to determine routing key
        MyCommand payload = (MyCommand) command.payload();
        return payload.tenantId() + ":" + payload.entityId(); // <1>
    }
}
// end::custom-routing-strategy[]
