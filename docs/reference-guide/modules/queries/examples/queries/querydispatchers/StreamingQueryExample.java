package queries.querydispatchers;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.Publisher;

import java.util.List;

class StreamingQueryExample {

    private CardRepository cardRepository;
    private QueryGateway queryGateway;

    // tag::streaming-query-example[]
    @QueryHandler
    public List<CardSummary> handle(FetchCardSummariesQuery query) {
        return cardRepository.findAll(); // <1>
    }

    public Publisher<CardSummary> consumer(FetchCardSummariesQuery query) {
        return queryGateway.streamingQuery(query, CardSummary.class); // <2>
    }
    // end::streaming-query-example[]
}
