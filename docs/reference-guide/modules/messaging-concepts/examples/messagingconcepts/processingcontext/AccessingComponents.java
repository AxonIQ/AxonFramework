package messagingconcepts.processingcontext;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryBus;

class AccessingComponents {

    // tag::accessing-components[]
    @EventHandler
    public void on(OrderPlacedEvent event, ProcessingContext context) {
        // Access framework components
        EventBus eventBus = context.component(EventBus.class);
        CommandGateway gateway = context.component(CommandGateway.class);

        // Access named components
        QueryBus queryBus = context.component(QueryBus.class, "myQueryBus");

        // Use components
        gateway.send(new ProcessOrderCommand(event.getOrderId()));
    }
    // end::accessing-components[]
}
