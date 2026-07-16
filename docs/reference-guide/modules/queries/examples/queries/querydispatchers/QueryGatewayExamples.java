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
