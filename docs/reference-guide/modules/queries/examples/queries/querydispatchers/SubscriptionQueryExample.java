package queries.querydispatchers;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

class SubscriptionQueryExample {

    private CommandGateway commandGateway;
    private QueryGateway queryGateway;

    void issueAndSubscribeToCardSummaries(int amount, int offset, int limit, CardSummaryFilter filter) {
        // tag::issuing-subscription-query[]
        // <1>
        commandGateway.sendAndWait(new IssueCardCommand("gc1", amount));

        // <2>
        FetchCardSummariesQuery query =
            new FetchCardSummariesQuery(offset, limit, filter);

        // <3>
        Publisher<CardSummary> results = queryGateway.subscriptionQuery(
            query,
            CardSummary.class
        );

        // <4>
        Disposable subscription = Flux.from(results)
            .subscribe(
                cardSummary -> System.out.println("Received: " + cardSummary),
                error -> System.err.println("Error: " + error),
                () -> System.out.println("Completed")
            );

        // <5>
        commandGateway.sendAndWait(new RedeemCardCommand("gc1", amount));

        // <6>
        // When done, cancel the subscription
        subscription.dispose();
        // end::issuing-subscription-query[]
    }
}
