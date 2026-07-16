/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
