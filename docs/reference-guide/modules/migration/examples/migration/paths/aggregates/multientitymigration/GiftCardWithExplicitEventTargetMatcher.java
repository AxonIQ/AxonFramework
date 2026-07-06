package migration.paths.aggregates.multientitymigration;

import java.util.ArrayList;
import java.util.List;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.modelling.entity.annotation.EntityMember;
import org.axonframework.modelling.entity.annotation.RoutingKeyEventTargetMatcherDefinition;

@EventSourcedEntity(tagKey = "cardId")
public class GiftCardWithExplicitEventTargetMatcher {

    // tag::explicit-event-target-matcher[]
    @EntityMember(
        routingKey = "transactionId",
        eventTargetMatcher = RoutingKeyEventTargetMatcherDefinition.class // <1>
    )
    private List<Transaction> transactions = new ArrayList<>();
    // end::explicit-event-target-matcher[]
}
