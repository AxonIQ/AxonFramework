package messagingconcepts.supportedparameters;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;

class EventHandlerQueryUpdateEmitterExample {

    private final Map<String, CardSummary> cardSummaryStorage = new ConcurrentHashMap<>();

    // tag::event-handler-query-update-emitter[]
    @EventHandler
    public void on(CardRedeemedEvent event, QueryUpdateEmitter emitter) {

        // Update the model
        CardSummary summary = cardSummaryStorage.get(event.getCardId());
        summary.setRemainingValue(event.getRemainingValue());

        // Emit update to subscription queries
        emitter.emit(FetchCardSummaryQuery.class,
                    query -> query.getCardSummaryId().equals(event.getCardId()),
                    summary);
    }
    // end::event-handler-query-update-emitter[]
}
