package commands.configuration.handlers;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.repository.Repository;

// tag::spring-command-handling-component[]
import org.springframework.stereotype.Component;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

@Component
public class GiftCardCommandHandler {

    private final Repository<String, GiftCard> giftCardRepository;

    public GiftCardCommandHandler(Repository<String, GiftCard> giftCardRepository) {
        this.giftCardRepository = giftCardRepository;
    }

    @CommandHandler
    public void handle(CancelCardCommand cmd,
                      ProcessingContext context,
                      EventAppender eventAppender) {
        giftCardRepository.load(cmd.cardId(), context)
            .thenAccept(managedCard -> {
                GiftCard card = managedCard.entity();
                if (card.canBeCancelled()) {
                    eventAppender.append(new CardCancelledEvent(cmd.cardId()));
                }
            });
    }
}
// end::spring-command-handling-component[]
