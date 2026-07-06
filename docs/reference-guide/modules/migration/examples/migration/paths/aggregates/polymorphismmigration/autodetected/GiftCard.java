package migration.paths.aggregates.polymorphismmigration.autodetected;

// tag::autodetected-entity[]
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;

@EventSourcedEntity(
    tagKey = "cardId",
    concreteTypes = {
            OpenLoopGiftCard.class,
            RechargeableGiftCard.class
    }
)
public class GiftCard extends Card {
    // ...

    @EntityCreator
    protected GiftCard(@InjectEntityId String cardId) {
        // ...
    }
}

// end::autodetected-entity[]
