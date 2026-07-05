package queries.queryhandlers.emitter;

import java.util.Map;

import org.springframework.stereotype.Component;

// tag::query-update-emitter[]
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

@Component
public class CardSummaryProjection {
    // end::query-update-emitter[]

    private Map<String, CardSummary> cardSummaryStorage;

    // tag::query-update-emitter[]

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
}
// end::query-update-emitter[]

class CardRedeemedEvent {

    private final String cardId;
    private final int remainingValue;

    CardRedeemedEvent(String cardId, int remainingValue) {
        this.cardId = cardId;
        this.remainingValue = remainingValue;
    }

    String getCardId() {
        return cardId;
    }

    int getRemainingValue() {
        return remainingValue;
    }
}

class CardSummary {

    private int remainingValue;

    void setRemainingValue(int remainingValue) {
        this.remainingValue = remainingValue;
    }
}

class FetchCardSummaryQuery {

    private final String cardSummaryId;

    FetchCardSummaryQuery(String cardSummaryId) {
        this.cardSummaryId = cardSummaryId;
    }

    String getCardSummaryId() {
        return cardSummaryId;
    }
}
