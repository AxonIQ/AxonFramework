package queries.configuration.registration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

// tag::card-summary-projection[]
public class CardSummaryProjection {

    private final Map<String, CardSummary> cardSummaryStorage = new ConcurrentHashMap<>();

    @QueryHandler
    public CardSummary handle(FetchCardSummaryQuery query) {
        // Retrieve CardSummary instance, for example from a repository
        return cardSummaryStorage.get(query.getCardSummaryId());
    }
}
// end::card-summary-projection[]
