package queries.querydispatchers;

import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import reactor.core.publisher.Flux;

import java.time.Duration;

class StreamingErrorHandlingExample {

    private ReactiveCardRepository reactiveCardRepository;

    // tag::streaming-error-handling[]
    @QueryHandler
    public Flux<CardSummary> handle(FetchCardSummariesQuery query) {
        return reactiveCardRepository.findAll()
                                     .timeout(Duration.ofSeconds(5));
    }
    // end::streaming-error-handling[]
}
