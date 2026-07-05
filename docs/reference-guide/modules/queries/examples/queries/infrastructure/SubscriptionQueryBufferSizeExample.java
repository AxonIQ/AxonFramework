package queries.infrastructure;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.Publisher;

class SubscriptionQueryBufferSizeExample {

    void demonstrateBufferSizes(QueryGateway queryGateway, String cardId) {
        // tag::subscription-query-buffer-size[]
        // Default buffer size based on Reactor's Queues.SMALL_BUFFER_SIZE constant
        Publisher<CardSummary> results = queryGateway.subscriptionQuery(
            new FetchCardSummaryQuery(cardId),
            CardSummary.class
        );

        // Custom buffer size
        Publisher<CardSummary> customBufferResults = queryGateway.subscriptionQuery(
            new FetchCardSummaryQuery(cardId),
            CardSummary.class,
            512  // buffer size
        );
        // end::subscription-query-buffer-size[]
    }
}
