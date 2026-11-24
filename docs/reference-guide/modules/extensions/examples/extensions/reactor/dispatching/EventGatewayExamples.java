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

// tag::publish-events[]

import org.axonframework.extension.reactor.messaging.eventhandling.gateway.ReactorEventGateway;
import reactor.core.publisher.Mono;

class OrderEventPublisher {

    private final ReactorEventGateway reactiveEventGateway;

    OrderEventPublisher(ReactorEventGateway reactiveEventGateway) {
        this.reactiveEventGateway = reactiveEventGateway;
    }

    public Mono<Void> publishOrderPlaced(String orderId) {
        return reactiveEventGateway.publish(new OrderPlacedEvent(orderId));
    }
}
// end::publish-events[]
