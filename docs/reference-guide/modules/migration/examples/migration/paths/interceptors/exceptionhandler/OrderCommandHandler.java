package migration.paths.interceptors.exceptionhandler;

import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;

// tag::exception-handler-migration[]
public class OrderCommandHandler {

    @ExceptionHandler(resultType = IllegalStateException.class)
    public void handleIllegalState(IllegalStateException exception) {
        // Handle exception for all commands on this component
    }

    // Narrow to a specific exception type
    @ExceptionHandler(resultType = ValidationException.class)
    public void handleValidation(ValidationException exception) {
        // Only handles ValidationException (and its subtypes)
    }
}
// end::exception-handler-migration[]
