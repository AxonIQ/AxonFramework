package commands.commanddispatchers;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.CommandResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class AsyncSendExample {

    // tag::async-send[]
    private CommandGateway commandGateway; // <1>

    public void sendCommand() {
        String cardId = UUID.randomUUID().toString();

        // <2>
        CommandResult result = commandGateway.send(new IssueCardCommand(cardId, 100, "shopId"));

        // <3>
        result.onSuccess(String.class, cardNumber -> {
            System.out.println("Card issued with number: " + cardNumber);
        }).onError(exception -> {
            System.err.println("Command failed: " + exception.getMessage());
        });

        // <4>
        CompletableFuture<String> futureResult = result.resultAs(String.class);
    }
    // omitted class and constructor
    // end::async-send[]
}
