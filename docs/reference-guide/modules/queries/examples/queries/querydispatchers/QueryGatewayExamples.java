package queries.querydispatchers;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;

import java.util.List;
import java.util.concurrent.CompletableFuture;

class QueryGatewayExamples {

    private QueryGateway queryGateway;

    void singleResultQuery() {
        // tag::single-result-query[]
        // Query for a single result
        CompletableFuture<String> result = queryGateway.query(
            new FetchItemQuery("item-123"),
            String.class
        );

        result.thenAccept(item -> System.out.println("Item: " + item));
        // end::single-result-query[]
    }

    void multipleResultsQuery() {
        // tag::multiple-results-query[]
        // Query for multiple results
        CompletableFuture<List<String>> results = queryGateway.queryMany(
            new FetchItemsQuery("criteria"),
            String.class
        );

        results.thenAccept(items -> items.forEach(System.out::println));
        // end::multiple-results-query[]
    }
}
