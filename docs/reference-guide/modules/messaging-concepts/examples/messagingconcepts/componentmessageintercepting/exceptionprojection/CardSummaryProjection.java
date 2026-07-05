package messagingconcepts.componentmessageintercepting.exceptionprojection;

import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::exception-projection[]
class CardSummaryProjection {
    // end::exception-projection[]

    private static final Logger log = LoggerFactory.getLogger(CardSummaryProjection.class);
    // tag::exception-projection[]

    // Event handlers and query handlers omitted

    @ExceptionHandler
    public void handleAll(Exception exception) {
        // Handles all exceptions thrown within this component
    }

    @ExceptionHandler
    public void handleIllegalArgumentExceptions(IllegalArgumentException exception) {
        // Handles all IllegalArgumentExceptions within this component
    }

    @ExceptionHandler(resultType = IllegalArgumentException.class)
    public void handleIllegalArgumentExceptions(Exception exception) {
        // Equivalent: handles IllegalArgumentExceptions using the resultType attribute
    }

    @ExceptionHandler
    public void logFailedEvent(EventMessage event, Exception exception) {
        // Access the full event message for cross-cutting concerns such as logging
        log.warn("Event {} failed: {}", event.type().qualifiedName(), exception.getMessage());
    }
}
// end::exception-projection[]
