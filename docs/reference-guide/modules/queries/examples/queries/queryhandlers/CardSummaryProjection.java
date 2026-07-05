package queries.queryhandlers;

import java.util.Map;

// tag::card-summary-projection[]
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class CardSummaryProjection {

    private Map<String, CardSummary> cardSummaryStorage;

    @QueryHandler // <1>
    public CardSummary handle(FetchCardSummaryQuery query) { // <2>
        return cardSummaryStorage.get(query.getCardSummaryId());
    }
    // omitted CardSummary event handlers which update the model
}
// end::card-summary-projection[]

class CardSummary {
}
