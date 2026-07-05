package messagingconcepts.supportedparameters;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

class BasicCommandHandlerExample {

    // tag::command-handler-event-appender[]
    @CommandHandler
    public void handle(PlaceOrderCommand command,
                       EventAppender appender) {
        // Make business decision and append event...
        PaymentRequestedEvent event = new PaymentRequestedEvent(
                command.orderId(),
                command.amount()
        );
        appender.append(event);
    }
    // end::command-handler-event-appender[]
}
