package commands.commanddispatchers;

import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.commandhandling.gateway.CommandResult;
import org.axonframework.messaging.core.Metadata;

import java.util.concurrent.CompletableFuture;

class DispatcherApiExample {

    private CommandDispatcher dispatcher;

    void sendCommand(Object command) {
        // tag::dispatcher-send[]
        // Send command and get CommandResult
        CommandResult result = dispatcher.send(command);

        // end::dispatcher-send[]
    }

    void sendWithMetadata(Object command, Metadata metadata) {
        // tag::dispatcher-send-metadata[]
        // Send command with metadata
        CommandResult result = dispatcher.send(command, metadata);

        // end::dispatcher-send-metadata[]
    }

    void sendAndGetFuture(Object command) {
        // tag::dispatcher-send-future[]
        // Send command and get CompletableFuture with expected type
        CompletableFuture<String> future = dispatcher.send(command, String.class);

        // end::dispatcher-send-future[]
    }

    void attachHandlers(Object command) {
        // tag::dispatcher-send-handlers[]
        // Attach handlers to the result
        dispatcher.send(command)
                 .onSuccess(String.class, cardNumber -> {
                     // Handle success
                 })
                 .onError(exception -> {
                     // Handle error
                 });
        // end::dispatcher-send-handlers[]
    }
}
