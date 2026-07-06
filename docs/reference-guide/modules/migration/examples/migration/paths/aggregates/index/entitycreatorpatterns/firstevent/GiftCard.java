package migration.paths.aggregates.index.entitycreatorpatterns.firstevent;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

@EventSourcedEntity
public class GiftCard {

    private String cardId;
    private int remainingValue;

    // tag::entity-creator-firstevent[]
    @EntityCreator
    public GiftCard(CardIssuedEvent event) {
        this.cardId = event.cardId();
        this.remainingValue = event.amount();
    }
    // end::entity-creator-firstevent[]
}
