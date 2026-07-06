package migration.paths.aggregates.index.creationpolicycreateifmissing;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;

@EventSourcedEntity
public class GiftCard {

    @EntityCreator
    protected GiftCard() {
    }

    // tag::creation-policy-create-if-missing[]
    @CommandHandler
    public static void handle(IssueGiftCard cmd,
                              EventAppender eventAppender,
                              @InjectEntity @Nullable GiftCard giftCard) {
        if (giftCard != null) {
            throw new IllegalStateException("GiftCard already exists");
        }
        eventAppender.append(new GiftCardIssued(cmd.cardId(), cmd.amount()));
    }
    // end::creation-policy-create-if-missing[]
}
