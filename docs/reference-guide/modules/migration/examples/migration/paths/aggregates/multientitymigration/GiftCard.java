package migration.paths.aggregates.multientitymigration;

// tag::gift-card-entity[]
import java.util.ArrayList;
import java.util.List;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.entity.annotation.EntityMember;

@EventSourcedEntity(tagKey = "cardId")
public class GiftCard {

    private String cardId;

    @EntityMember(routingKey = "transactionId") // <1>
    private List<Transaction> transactions = new ArrayList<>();

    @CommandHandler
    public void handle(RedeemCardCommand cmd, EventAppender eventAppender) {
        // ... validation logic
        eventAppender.append(new CardRedeemedEvent(cardId, cmd.amount(), cmd.transactionId()));
    }

    @EventSourcingHandler
    public void on(CardRedeemedEvent event) {
        this.transactions.add(new Transaction(event.transactionId(), event.amount())); // <2>
    }

    @EntityCreator
    protected GiftCard(@InjectEntityId String cardId) {
        this.cardId = cardId;
    }
}
// end::gift-card-entity[]
