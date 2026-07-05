package queries.querydispatchers;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import reactor.core.publisher.Flux;

class NativeStreamingExample {

    private ReactiveCardRepository reactiveCardRepository;

    // tag::native-streaming-flux[]
    @QueryHandler
    public Flux<CardSummary> handle(FetchCardSummariesQuery query) {
        return reactiveCardRepository.findAll();
    }
    // end::native-streaming-flux[]
}
