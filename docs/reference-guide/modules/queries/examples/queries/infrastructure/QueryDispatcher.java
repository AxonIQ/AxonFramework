package queries.infrastructure;

// tag::dispatching-and-subscribing[]
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

public class QueryDispatcher {

    public void dispatchFetchCard(QueryGateway queryGateway) {
        String cardId = "...";
        // Client-side subscription query
        Publisher<CardSummary> results = queryGateway.subscriptionQuery(
                new FetchCardSummaryQuery(cardId),
                CardSummary.class
        );

        // Subscribe using Reactor (requires reactor-core dependency)
        Disposable subscription = Flux.from(results)
                                      .doOnNext(summary -> System.out.println("Received: " + summary))
                                      .doOnComplete(() -> System.out.println("No more updates"))
                                      .doOnError(error -> System.err.println("Error: " + error))
                                      .subscribe();

        // Later: cancel the subscription
        subscription.dispose();
    }
}
// end::dispatching-and-subscribing[]
