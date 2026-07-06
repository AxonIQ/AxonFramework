package migration.paths.aggregates.index.entitycreatorpatterns.identifier;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;

@EventSourcedEntity
public class GiftCard {

    private String cardId;

    // tag::entity-creator-identifier[]
    @EntityCreator
    public GiftCard(@InjectEntityId String cardId) {
        this.cardId = cardId;
    }
    // end::entity-creator-identifier[]
}
