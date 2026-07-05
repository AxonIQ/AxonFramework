package messagingconcepts.processingcontext;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

class EventAppenderPropagation {

    // tag::event-appender[]
    @CommandHandler
    public void handle(PlaceOrderCommand command,
                       EventAppender appender) {
        // EventAppender uses the context automatically
        // Metadata and correlation data from context are propagated
        appender.append(new OrderPlacedEvent(command.getOrderId()));
    }
    // end::event-appender[]
}
