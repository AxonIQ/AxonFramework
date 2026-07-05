package messagingconcepts.supportedparameters;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class EventHandlerProcessingContextExample {

    // tag::event-handler-processing-context[]
    @EventHandler
    public void on(OrderPlacedEvent event,
                   ProcessingContext context) {
        // Register cleanup action
        context.whenComplete(pc -> cleanupResources());
        processEvent(event);
    }
    // end::event-handler-processing-context[]

    private void processEvent(OrderPlacedEvent event) {
    }

    private void cleanupResources() {
    }
}
