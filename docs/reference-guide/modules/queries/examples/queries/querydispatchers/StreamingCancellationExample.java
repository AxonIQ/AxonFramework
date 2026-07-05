package queries.querydispatchers;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.function.Predicate;

class StreamingCancellationExample {

    private QueryGateway queryGateway;

    // tag::streaming-cancellation[]
    public Publisher<CardSummary> consumer(FetchCardSummariesQuery query, Predicate<CardSummary> somePredicate) {
        return Flux.from(queryGateway.streamingQuery(query, CardSummary.class))
                   .take(100)
                   .takeUntil(message -> somePredicate.test(message));
    }
    // end::streaming-cancellation[]
}
