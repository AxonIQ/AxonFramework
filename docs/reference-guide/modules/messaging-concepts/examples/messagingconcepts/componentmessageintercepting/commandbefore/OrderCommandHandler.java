package messagingconcepts.componentmessageintercepting.commandbefore;

import messagingconcepts.componentmessageintercepting.PlaceOrderCommand;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::command-interceptor-before[]
import org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

public class OrderCommandHandler {
    // end::command-interceptor-before[]

    private static final Logger log = LoggerFactory.getLogger(OrderCommandHandler.class);
    // tag::command-interceptor-before[]

    @CommandHandlerInterceptor
    void logCommand(CommandMessage command) {
        log.info("Handling command: {}", command.type().qualifiedName());
    }

    @CommandHandler
    void handle(PlaceOrderCommand command, ProcessingContext context) {
        // Handle the command
    }
}
// end::command-interceptor-before[]
