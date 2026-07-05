package messagingconcepts.processingcontext;

import java.time.Instant;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

class AccessingCurrentMessage {

    // tag::accessing-current-message[]
    @CommandHandler
    public void handle(MyCommand command, ProcessingContext context) {
        // Retrieve the current message
        Message message = Message.fromContext(context);

        // Cast to specific message type if needed
        CommandMessage commandMessage = (CommandMessage) message;

        // Access message properties
        String messageId = message.identifier();
        Metadata metadata = message.metadata();
        Instant timestamp = ((EventMessage) message).timestamp(); // For events
    }
    // end::accessing-current-message[]
}
