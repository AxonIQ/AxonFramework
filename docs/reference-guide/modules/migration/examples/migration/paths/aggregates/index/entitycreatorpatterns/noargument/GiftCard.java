package migration.paths.aggregates.index.entitycreatorpatterns.noargument;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;

@EventSourcedEntity
public class GiftCard {

    private String cardId;
    private int remainingValue;

    // tag::entity-creator-noarg[]
    @EntityCreator
    public GiftCard() { }
    // end::entity-creator-noarg[]
}
