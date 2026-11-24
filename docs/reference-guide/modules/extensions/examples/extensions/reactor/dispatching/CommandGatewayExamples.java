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

// tag::send-controller[]

import org.axonframework.extension.reactor.messaging.commandhandling.gateway.ReactorCommandGateway;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
class OrderCommandController {

    private final ReactorCommandGateway reactiveCommandGateway;

    OrderCommandController(ReactorCommandGateway reactiveCommandGateway) {
        this.reactiveCommandGateway = reactiveCommandGateway;
    }

    @PostMapping
    public Mono<OrderConfirmation> placeOrder(@RequestBody PlaceOrderCommand command) {
        return reactiveCommandGateway.send(command, OrderConfirmation.class);
    }
}
// end::send-controller[]

class OrderShipper {

    private final ReactorCommandGateway reactiveCommandGateway;

    OrderShipper(ReactorCommandGateway reactiveCommandGateway) {
        this.reactiveCommandGateway = reactiveCommandGateway;
    }

    // tag::send-without-result[]
    public Mono<Void> ship(ShipOrderCommand command) {
        return reactiveCommandGateway.send(command);
    }
    // end::send-without-result[]
}

// tag::send-and-forget[]
class CommandDispatcher {

    private final ReactorCommandGateway reactiveCommandGateway;

    CommandDispatcher(ReactorCommandGateway reactiveCommandGateway) {
        this.reactiveCommandGateway = reactiveCommandGateway;
    }

    public void sendAndForget(ShipOrderCommand command) {
        reactiveCommandGateway.send(command)
                              .subscribe();
    }
}
// end::send-and-forget[]
