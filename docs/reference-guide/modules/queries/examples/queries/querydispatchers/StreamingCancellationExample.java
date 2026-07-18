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
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.function.Predicate;

class StreamingCancellationExample {

    private QueryGateway queryGateway;

    // tag::streaming-cancellation[]
    public Publisher<CardSummary> consumer(FetchCardSummariesQuery query, Predicate<CardSummary> somePredicate) {
        return Flux.from(queryGateway.streamingQuery(query, CardSummary.class))
                   .take(100)
                   .takeUntil(message -> somePredicate.test(message));
    }
    // end::streaming-cancellation[]
}
