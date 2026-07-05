package queries.queryhandlers.matching;

import java.util.Map;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class MapRepresentationHandler {

    private Map<String, CardSummary> cardSummaryStorage;

    // tag::handle-map-representation[]
    @QueryHandler(queryName = "giftcard.FetchCardSummary") // <1>
    public CardSummary handle(Map<String, Object> queryData) { // <2>
        String cardId = (String) queryData.get("cardSummaryId");
        return cardSummaryStorage.get(cardId);
    }
    // end::handle-map-representation[]
}
