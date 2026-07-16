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
