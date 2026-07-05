package messagingconcepts.processingcontext;

import java.util.concurrent.CompletableFuture;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.commandhandling.gateway.CommandResult;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CommandDispatcherPropagation {

    private static final Logger logger = LoggerFactory.getLogger(CommandDispatcherPropagation.class);

    // tag::command-dispatcher[]
    @EventHandler
    public CompletableFuture<Void> on(OrderPlacedEvent event,
                                      CommandDispatcher commandDispatcher) {
        // CommandDispatcher is already bound to the current ProcessingContext
        // Correlation data propagates automatically
        CommandResult result = commandDispatcher.send(
            new ProcessOrderCommand(event.getOrderId())
        );

        // Return the CompletableFuture so the handler only completes when the command finishes
        return result.getResultMessage()
                     .thenAccept(r -> logger.info("Command processed successfully"))
                     .exceptionally(ex -> {
                         logger.error("Command failed: {}", ex.getMessage());
                         return null;
                     });
    }
    // end::command-dispatcher[]
}
