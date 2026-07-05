package messagingconcepts.supportedparameters;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

class QueryHandlerProcessingContextExample {

    private final Map<String, CardSummary> cardSummaryStorage = new ConcurrentHashMap<>();

    // tag::query-handler-processing-context[]
    @QueryHandler
    public CardSummary handle(FetchCardSummaryQuery query, ProcessingContext context) {
        // Access resources registered with the ProcessingContext
        SomeService service = context.getResource(SomeService.RESOURCE_KEY);

        return cardSummaryStorage.get(query.getCardSummaryId());
    }
    // end::query-handler-processing-context[]
}
