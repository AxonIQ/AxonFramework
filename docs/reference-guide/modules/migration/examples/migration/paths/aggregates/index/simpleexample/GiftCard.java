package migration.paths.aggregates.index.simpleexample;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

// tag::simple-example[]
@EventSourcedEntity // <1>
public class GiftCard {

    private String cardId;
    private int remainingValue;

    @EntityCreator // <2>
    public GiftCard() {
    }

    @CommandHandler // <3>
    public static void handle(IssueCardCommand cmd, EventAppender eventAppender) {
        eventAppender.append(new CardIssuedEvent(cmd.cardId(), cmd.amount()));
    }

    @CommandHandler // <4>
    public void handle(RedeemCardCommand cmd, EventAppender eventAppender) {
        if (cmd.amount() > remainingValue) {
            throw new IllegalStateException("Insufficient funds");
        }
        eventAppender.append(new CardRedeemedEvent(cardId, cmd.amount()));
    }

    @EventSourcingHandler // <5>
    public void on(CardIssuedEvent event) {
        this.cardId = event.cardId();
        this.remainingValue = event.amount();
    }

    @EventSourcingHandler
    public void on(CardRedeemedEvent event) {
        this.remainingValue -= event.amount();
    }
}
// end::simple-example[]
