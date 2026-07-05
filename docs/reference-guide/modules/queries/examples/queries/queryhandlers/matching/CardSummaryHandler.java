package queries.queryhandlers.matching;

import java.util.Map;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class CardSummaryHandler {

    private Map<String, CardSummary> cardSummaryStorage;

    // tag::handle-fetch-card-summary[]
    @QueryHandler
    public CardSummary handle(FetchCardSummaryQuery query) {
        return cardSummaryStorage.get(query.getCardSummaryId());
    }
    // end::handle-fetch-card-summary[]
}
