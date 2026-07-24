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
package commands.infrastructure.repositories;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.repository.Repository;
import org.springframework.stereotype.Component;

// tag::repository-command-handler[]
@Component
public class OrderCommandHandler {

    private final Repository<String, Order> orderRepository;

    public OrderCommandHandler(Repository<String, Order> orderRepository) {
        this.orderRepository = orderRepository;
    }

    @CommandHandler
    public void handle(ShipOrderCommand command,
                      ProcessingContext context,
                      EventAppender eventAppender) {
        // Load the entity
        orderRepository.load(command.orderId(), context)
            .thenAccept(managedOrder -> {
                Order order = managedOrder.entity();
                // Apply business logic
                if (order.canShip()) {
                    eventAppender.append(new OrderShippedEvent(command.orderId()));
                }
            });
    }
}
// end::repository-command-handler[]
