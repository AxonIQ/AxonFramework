package queries.configuration.registration.springboot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// tag::query-handler-springboot[]
import org.springframework.stereotype.Component;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

@Component
public class CardSummaryProjection {

    private final Map<String, CardSummary> cardSummaryStorage = new ConcurrentHashMap<>();

    @QueryHandler
    public CardSummary handle(FetchCardSummaryQuery query) {
        // Retrieve CardSummary instance, for example from a repository
        return cardSummaryStorage.get(query.getCardSummaryId());
    }
}
// end::query-handler-springboot[]
