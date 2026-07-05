package commands.commanddispatchers;

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.MessageType;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class CommandBusDispatchExample {

    // tag::dispatching-with-commandbus[]
    private CommandBus commandBus; // <1>

    public void dispatchCommands() {
        // <2>
        MessageType commandType = new MessageType(IssueCardCommand.class);
        IssueCardCommand payload = new IssueCardCommand(UUID.randomUUID().toString(), 100, "shopId");
        CommandMessage commandMessage = new GenericCommandMessage(commandType, payload);

        // <3>
        CompletableFuture<CommandResultMessage> resultFuture = commandBus.dispatch(
                commandMessage,
                null // <4>
        );

        // <5>
        resultFuture.whenComplete((resultMsg, exception) -> {
            if (exception != null) {
                // Handle command execution failure
            } else {
                Object commandResult = resultMsg.payload();
                // Handle successful result
            }
        });
    }
    // omitted class and constructor
    // end::dispatching-with-commandbus[]
}
