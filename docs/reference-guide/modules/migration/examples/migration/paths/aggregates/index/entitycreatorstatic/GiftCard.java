package migration.paths.aggregates.index.entitycreatorstatic;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

// tag::entity-creator-static[]
// AF5
@EventSourcedEntity
public class GiftCard {
    private String cardId; // No annotation needed on the field

    @EntityCreator
    public GiftCard() { } // <1>

    @CommandHandler
    public static void handle(IssueCardCommand cmd, EventAppender eventAppender) {
        eventAppender.append(new CardIssuedEvent(cmd.cardId(), cmd.amount()));
    }
}
// end::entity-creator-static[]
