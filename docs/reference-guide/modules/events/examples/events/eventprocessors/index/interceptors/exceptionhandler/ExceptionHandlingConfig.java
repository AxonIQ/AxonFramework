package events.eventprocessors.index.interceptors.exceptionhandler;

// The import block is indented to the depth of the nested method below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::with-exception-handler-import[]
    import org.axonframework.messaging.core.MessageStream;
    import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;

// end::with-exception-handler-import[]
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ExceptionHandlingConfig {

    private final Logger log = LoggerFactory.getLogger(ExceptionHandlingConfig.class);

    // tag::with-exception-handler[]
    private EventHandlingComponentsConfigurer.CompletePhase configureHandlingComponents(
            EventHandlingComponentsConfigurer.RequiredComponentPhase componentConfigurer
    ) {
        return componentConfigurer.autodetected("orderHandler", cfg -> new OrderEventHandler())
                                  .withExceptionHandler(cfg -> (event, context, error) -> {
                                      log.warn("Handler failed for {}: {}", event.type().qualifiedName(), error.getMessage());
                                      return MessageStream.empty(); // suppress; use MessageStream.failed(error) to propagate
                                  });
    }
    // end::with-exception-handler[]
}

record OrderPlaced(String orderId) {

}

class OrderEventHandler {

    @org.axonframework.messaging.eventhandling.annotation.EventHandler
    void on(OrderPlaced event) {
        // handle order placement
    }
}
