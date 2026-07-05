package commands.commanddispatchers;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;

import java.util.UUID;

class SendAndWaitExample {

    // tag::send-and-wait[]
    private CommandGateway commandGateway;

    public void sendCommandAndWaitOnResult() {
        IssueCardCommand commandPayload = new IssueCardCommand(UUID.randomUUID().toString(), 100, "shopId");

        // <1>
        String result = commandGateway.sendAndWait(commandPayload, String.class);

        // <2>
        Object genericResult = commandGateway.sendAndWait(commandPayload);
    }
    // omitted class and constructor
    // end::send-and-wait[]
}
