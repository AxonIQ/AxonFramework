package messagingconcepts.processingcontext;

import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class BranchedContext {

    private static final ResourceKey<String> CORRELATION_ID_KEY = ResourceKey.withLabel("CorrelationId");

    // tag::branched-context[]
    @EventHandler
    public void on(OrderCreatedEvent event, ProcessingContext context) {
        // Create branched context with additional resource
        ProcessingContext enrichedContext = context.withResource(
            CORRELATION_ID_KEY,
            event.getOrderId()
        );

        // The enriched context has all resources from the original context
        // plus the new correlation ID
        // Lifecycle callbacks registered on either context affect both
    }
    // end::branched-context[]
}
