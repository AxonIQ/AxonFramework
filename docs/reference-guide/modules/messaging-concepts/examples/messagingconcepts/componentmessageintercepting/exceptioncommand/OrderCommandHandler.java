package messagingconcepts.componentmessageintercepting.exceptioncommand;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::exception-command[]
class OrderCommandHandler {
    // end::exception-command[]

    private static final Logger log = LoggerFactory.getLogger(OrderCommandHandler.class);
    // tag::exception-command[]

    // Command handlers omitted

    @ExceptionHandler
    public void handleAll(Exception exception) {
        // Handles all exceptions thrown within this component
    }

    @ExceptionHandler
    public void handleIllegalStateExceptions(IllegalStateException exception) {
        // Handles all IllegalStateExceptions thrown within this component
    }

    @ExceptionHandler(resultType = IllegalStateException.class)
    public void handleIllegalStateExceptions(Exception exception) {
        // Equivalent: handles IllegalStateExceptions using the resultType attribute
    }

    @ExceptionHandler
    public void logFailedCommand(CommandMessage command, Exception exception) {
        // Access the full command message for cross-cutting concerns such as logging
        log.warn("Command {} failed: {}", command.type().qualifiedName(), exception.getMessage());
    }
}
// end::exception-command[]
