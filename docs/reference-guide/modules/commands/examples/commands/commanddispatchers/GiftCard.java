package commands.commanddispatchers;

import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

// tag::injecting-command-dispatcher[]
@EventSourced
public class GiftCard {

    @CommandHandler
    public void handle(RedeemCardCommand command, EventAppender eventAppender, CommandDispatcher dispatcher) { // <1>
        // Validate and apply event
        eventAppender.append(new CardRedeemedEvent(command.cardId(), command.amount()));

        // Dispatch another command using the dispatcher
        dispatcher.send(new SendThankYouEmailCommand(command.cardId())); // <2>
    }
}
// end::injecting-command-dispatcher[]
