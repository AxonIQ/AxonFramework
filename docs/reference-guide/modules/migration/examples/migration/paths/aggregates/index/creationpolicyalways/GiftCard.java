package migration.paths.aggregates.index.creationpolicyalways;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

@EventSourcedEntity
public class GiftCard {

    @EntityCreator
    protected GiftCard() {
    }

    // tag::creation-policy-always[]
    @CommandHandler
    public static void handle(IssueGiftCard cmd, EventAppender eventAppender) {
        eventAppender.append(new GiftCardIssued(cmd.cardId(), cmd.amount()));
    }
    // end::creation-policy-always[]
}
