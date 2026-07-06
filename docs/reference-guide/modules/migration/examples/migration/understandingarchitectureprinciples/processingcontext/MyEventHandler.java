package migration.understandingarchitectureprinciples.processingcontext;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class MyEventHandler {

    // tag::processing-context[]
    // Axon Framework 5: Explicit ProcessingContext
    @EventHandler
    public void handle(MyEvent event, ProcessingContext context) {
        // Context is explicit, can be reasoned about
        // Flows through all nested operations
    }
    // end::processing-context[]
}
