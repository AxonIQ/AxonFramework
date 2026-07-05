package events.eventprocessors.index.interceptors.annotated.exceptionhandler;

// tag::annotated-exception-handler[]
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.modelling.ConcurrencyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyEventHandler {

    private final Logger log = LoggerFactory.getLogger(MyEventHandler.class);

    @EventHandler
    void on(OrderPlaced event) {
        // may throw
    }

    @ExceptionHandler
    void onException(RuntimeException ex) {
        log.error("Handler failed: {}", ex.getMessage());
        // return normally to suppress the exception and continue processing
    }

    @ExceptionHandler(resultType = ConcurrencyException.class)
    void onConcurrencyFailure(ConcurrencyException ex) {
        // handle a specific exception type; other exceptions are not affected
    }
}
// end::annotated-exception-handler[]

record OrderPlaced(String orderId) {

}
