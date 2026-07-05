package queries.queryhandlers.parameters;

import java.util.Map;

import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

public class ProcessingContextExample {

    private Map<String, CardSummary> cardSummaryStorage;

    // tag::query-handler-processing-context[]
    @QueryHandler
    public CardSummary handle(FetchCardSummaryQuery query, ProcessingContext context) {
        // Access resources
        SomeService service = context.getResource(SomeService.RESOURCE_KEY);

        return cardSummaryStorage.get(query.getCardSummaryId());
    }
    // end::query-handler-processing-context[]
}

class SomeService {

    static final ResourceKey<SomeService> RESOURCE_KEY = ResourceKey.withLabel("someService");
}
