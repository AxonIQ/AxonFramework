package messagingconcepts.processingcontext;

import java.util.concurrent.CompletableFuture;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AccessingContextHandlers {

    private static final Logger logger = LoggerFactory.getLogger(AccessingContextHandlers.class);

    // tag::accessing-context[]
    @CommandHandler
    public OrderResult handle(PlaceOrderCommand command,
                              ProcessingContext context) {
        // Access the context to register callbacks, manage resources, etc.
        context.onCommit(ctx -> {
            logger.info("Order placed successfully");
            return CompletableFuture.completedFuture(null);
        });

        return processOrder(command);
    }

    @EventHandler
    public void on(OrderPlacedEvent event,
                   ProcessingContext context) {
        // Register cleanup action
        context.doFinally(ctx -> releaseResources());

        updateProjection(event);
    }
    // end::accessing-context[]

    private OrderResult processOrder(PlaceOrderCommand command) {
        return new OrderResult();
    }

    private void updateProjection(OrderPlacedEvent event) {
        // Update the read model with the placed order.
    }

    private void releaseResources() {
        // Free any resources acquired during processing.
    }
}
