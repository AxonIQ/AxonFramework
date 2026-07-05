package queries.queryhandlers.matching;

import java.util.Map;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class PayloadConversionHandlers {

    private Map<String, CardSummary> cardSummaryStorage;
    private Map<String, CardDetails> detailedCardStorage;

    // tag::payload-conversion-handlers[]
    // Handler expecting the query as a specific type
    @QueryHandler
    public CardSummary handle(FetchCardSummaryQuery query) {
        return cardSummaryStorage.get(query.getCardSummaryId());
    }

    // Another handler in another application could receive the same query in a different representation
    @QueryHandler(queryName = "giftcard.FetchCardSummary") // Must specify queryName!
    public CardDetails handle(Map<String, Object> query) {
        String cardId = (String) query.get("cardSummaryId");
        return detailedCardStorage.get(cardId);
    }
    // end::payload-conversion-handlers[]
}

class CardDetails {
}
