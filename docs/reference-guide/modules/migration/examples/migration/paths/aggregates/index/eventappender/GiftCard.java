package migration.paths.aggregates.index.eventappender;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

@EventSourcedEntity
public class GiftCard {

    private String id;

    // tag::event-appender[]
    // Axon Framework 5
    @CommandHandler
    public void handle(RedeemCardCommand cmd, EventAppender eventAppender) {
        eventAppender.append(new CardRedeemedEvent(id, cmd.amount()));
    }
    // end::event-appender[]
}
