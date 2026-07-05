package messagingconcepts.messagecorrelation;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

class OrderCorrelationHandlers {

    // tag::message-origin-handlers[]
    // When handling a command that publishes an event:
    @CommandHandler
    public void handle(PlaceOrderCommand command, EventAppender appender) {
        // Command message id: "cmd-123"
        // Command has no correlationId yet (it's the root)

        // Create and publish event...
        appender.append(new OrderPlacedEvent());

        // Event metadata will contain:
        // - correlationId: "cmd-123" (from command id, since command had none)
        // - causationId: "cmd-123" (direct parent)
    }

    // When the event triggers another command:
    @EventHandler
    public void on(OrderPlacedEvent event, CommandDispatcher dispatcher) {
        // Event message id: "evt-456"
        // Event metadata:
        // - correlationId: "cmd-123"
        // - causationId: "cmd-123"

        // Create and send command...
        dispatcher.send(new ShipOrderCommand());

        // Command metadata will contain:
        // - correlationId: "cmd-123" (propagated from event)
        // - causationId: "evt-456" (immediate parent)
    }
    // end::message-origin-handlers[]
}

record PlaceOrderCommand() {
}

record OrderPlacedEvent() {
}

record ShipOrderCommand() {
}
