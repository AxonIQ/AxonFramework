package commands.commandhandlers;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

class InjectableParametersExample {

    // tag::injectable-parameters[]
    @CommandHandler
    public void handle(
        RenameCourse command,                   // <1>
        CommandMessage commandMessage,          // <2>
        Metadata metadata,                      // <3>
        @MetadataValue("userId") String userId, // <4>
        ProcessingContext processingContext,    // <5>
        EventAppender eventAppender,            // <6>
        CommandDispatcher commandDispatcher     // <7>
    ) {
        // Handler logic
    }
    // end::injectable-parameters[]
}
