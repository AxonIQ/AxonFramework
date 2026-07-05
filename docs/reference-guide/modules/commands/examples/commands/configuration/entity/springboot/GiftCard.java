package commands.configuration.entity.springboot;

import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

// tag::spring-event-sourced-entity[]
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;

@EventSourced
public class GiftCard {

    private String id;
    private int remainingValue;

    @CommandHandler
    public static GiftCard handle(IssueCardCommand cmd, EventAppender eventAppender) {
        GiftCard card = new GiftCard();
        eventAppender.append(new CardIssuedEvent(cmd.cardId(), cmd.amount()));
        return card;
    }

    @CommandHandler
    public void handle(RedeemCardCommand cmd, EventAppender eventAppender) {
        if (remainingValue >= cmd.amount()) {
            eventAppender.append(new CardRedeemedEvent(id, cmd.amount()));
        }
    }

    @EventSourcingHandler
    private void on(CardIssuedEvent event) {
        this.id = event.cardId();
        this.remainingValue = event.amount();
    }

    @EventSourcingHandler
    private void on(CardRedeemedEvent event) {
        this.remainingValue -= event.amount();
    }

    @EntityCreator
    protected GiftCard() {
        // Required no-arg constructor for reconstitution
    }
}
// end::spring-event-sourced-entity[]
