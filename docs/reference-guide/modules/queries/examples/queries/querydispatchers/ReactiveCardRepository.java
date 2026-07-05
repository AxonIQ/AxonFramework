package queries.querydispatchers;

import reactor.core.publisher.Flux;

/**
 * Supporting reactive repository used by the native-streaming-flux and streaming-error-handling samples on the
 * query-dispatchers page.
 */
public interface ReactiveCardRepository {

    Flux<CardSummary> findAll();
}
