package queries.infrastructure;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;

class CardSummaryProjectionWithInjectedEmitter {

    // tag::emitting-updates-injected[]
    @EventHandler
    public void on(CardRedeemedEvent event, QueryUpdateEmitter emitter) {
        // Axon automatically provides the context-aware emitter
        emitter.emit(FetchCardSummaryQuery.class,
                     query -> query.cardSummaryId().equals(event.cardId()),
                     new CardSummary(event.cardId(), event.amount()));
    }
    // end::emitting-updates-injected[]
}
