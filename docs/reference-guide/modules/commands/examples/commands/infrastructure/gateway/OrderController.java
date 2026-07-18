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
package commands.infrastructure.gateway;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

// tag::order-controller[]
@RestController
public class OrderController {

    private final CommandGateway commandGateway;

    public OrderController(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PostMapping("/orders")
    public CompletableFuture<String> createOrder(@RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = new CreateOrderCommand(
            UUID.randomUUID().toString(),
            request.getProductId(),
            request.getQuantity()
        );

        // Returns CompletableFuture<String> with the order ID
        return commandGateway.send(command, String.class);
    }

    @PostMapping("/orders/{id}/ship")
    public void shipOrder(@PathVariable String id) {
        // Fire and forget
        commandGateway.send(new ShipOrderCommand(id));
    }
}
// end::order-controller[]
