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

package extensions.reactor.dispatching;

// tag::query-controller[]

import org.axonframework.extension.reactor.messaging.queryhandling.gateway.ReactorQueryGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
class OrderQueryController {

    private final ReactorQueryGateway reactiveQueryGateway;

    OrderQueryController(ReactorQueryGateway reactiveQueryGateway) {
        this.reactiveQueryGateway = reactiveQueryGateway;
    }

    // The query's Mono is returned to the Spring controller. Subscribe control is given to Spring Framework.
    @GetMapping("/order")
    public Mono<OrderSummary> findOrder(FindOrderQuery query) {
        return reactiveQueryGateway.query(query, OrderSummary.class);
    }

    @GetMapping("/orders")
    public Mono<List<OrderSummary>> findOrders(FindOrdersQuery query) {
        return reactiveQueryGateway.queryMany(query, OrderSummary.class);
    }

    @GetMapping("/orders/stream")
    public Flux<OrderSummary> streamOrders(FindOrdersQuery query) {
        return reactiveQueryGateway.streamingQuery(query, OrderSummary.class);
    }
}
// end::query-controller[]

class OrderQueryRetrier {

    private final ReactorQueryGateway reactiveQueryGateway;

    OrderQueryRetrier(ReactorQueryGateway reactiveQueryGateway) {
        this.reactiveQueryGateway = reactiveQueryGateway;
    }

    // tag::query-retry[]
    public Mono<OrderSummary> findOrderWithRetries(FindOrderQuery query) {
        return reactiveQueryGateway.query(query, OrderSummary.class)
                                   .retry(5);
    }
    // end::query-retry[]
}

// tag::subscription-query[]
class OrderUpdateSubscriber {

    private final ReactorQueryGateway reactiveQueryGateway;

    OrderUpdateSubscriber(ReactorQueryGateway reactiveQueryGateway) {
        this.reactiveQueryGateway = reactiveQueryGateway;
    }

    // Emits the initial result first, followed by every update until the subscriber cancels
    public Flux<OrderSummary> subscribeToOrder(FindOrderQuery query) {
        return reactiveQueryGateway.subscriptionQuery(query, OrderSummary.class);
    }
}
// end::subscription-query[]
