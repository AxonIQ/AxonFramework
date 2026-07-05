package commands.commanddispatchers;

import org.axonframework.messaging.commandhandling.annotation.Command;

// tag::routing-key-with-command[]
@Command(routingKey = "cardId") // <1>
public record IssueCardCommand(String cardId, int amount, String shopId) {
}
// end::routing-key-with-command[]
