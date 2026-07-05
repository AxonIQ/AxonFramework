package messagingconcepts.processingcontext;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class LifecycleStateHandler {

    // tag::lifecycle-state[]
    @EventHandler
    public void on(OrderPlacedEvent event, ProcessingContext context) {
        if (context.isStarted()) {
            // Processing has started
        }

        if (context.isError()) {
            // An error occurred during processing
        }

        if (context.isCommitted()) {
            // Processing committed successfully
        }

        if (context.isCompleted()) {
            // Processing completed (success or failure)
        }
        // Process event...
    }
    // end::lifecycle-state[]
}
