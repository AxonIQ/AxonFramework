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
package messagingconcepts.exceptionhandling.exceptions;

import messagingconcepts.exceptionhandling.DatabaseUnavailableException;
import messagingconcepts.exceptionhandling.Inventory;
import messagingconcepts.exceptionhandling.Order;
import messagingconcepts.exceptionhandling.OrderRepository;
import messagingconcepts.exceptionhandling.PlaceOrderCommand;

// tag::infrastructure-exception[]
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.modelling.annotation.InjectEntity;

class InventoryAutomation {

    // Appropriate: Exception for infrastructure failure
    @CommandHandler
    public void handle(PlaceOrderCommand command,
                       @InjectEntity Inventory inventory) {
        try {
            orderRepository.save(new Order(command.order()));
        } catch (DatabaseUnavailableException e) {
            // Truly exceptional - database is down
            throw new CommandExecutionException(
                    "Unable to process order due to system unavailability",
                    e
            );
        }
    }
    // end::infrastructure-exception[]

    private final OrderRepository orderRepository = new OrderRepository();
    // tag::infrastructure-exception[]
}
// end::infrastructure-exception[]
