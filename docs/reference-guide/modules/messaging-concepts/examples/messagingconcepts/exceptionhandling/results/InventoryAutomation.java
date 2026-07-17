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
package messagingconcepts.exceptionhandling.results;

import messagingconcepts.exceptionhandling.Inventory;
import messagingconcepts.exceptionhandling.OrderPlacedEvent;
import messagingconcepts.exceptionhandling.OrderResult;
import messagingconcepts.exceptionhandling.PlaceOrderCommand;

// tag::result-object[]
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

class InventoryAutomation {

    // Good: Returns a result object
    @CommandHandler
    public OrderResult placeOrder(PlaceOrderCommand command,
                                  @InjectEntity Inventory inventory,
                                  EventAppender appender) {
        if (inventory.sufficientFor(command.productIds())) {
            return OrderResult.failed("Insufficient balance");
        }
        if (!isAuthorized(command.userId())) {
            return OrderResult.failed("User not authorized");
        }
        appender.append(new OrderPlacedEvent(orderId));
        // Process order
        return OrderResult.success(orderId);
    }
    // end::result-object[]

    private String orderId;

    private boolean isAuthorized(String userId) {
        return true;
    }
    // tag::result-object[]
}
// end::result-object[]
