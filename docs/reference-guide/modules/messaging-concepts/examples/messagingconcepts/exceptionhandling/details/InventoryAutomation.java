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
package messagingconcepts.exceptionhandling.details;

import messagingconcepts.exceptionhandling.Inventory;
import messagingconcepts.exceptionhandling.PlaceOrderCommand;

// tag::exception-details[]
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.modelling.annotation.InjectEntity;

import java.util.Map;

class InventoryAutomation {

    @CommandHandler
    public void handle(PlaceOrderCommand command,
                       @InjectEntity Inventory inventory,
                       EventAppender appender) {
        if (criticalSystemError()) {
            throw new CommandExecutionException(
                    "System unavailable",
                    null,
                    // Exception details...
                    Map.of(
                            "errorCode", "SYSTEM_UNAVAILABLE",
                            "retryable", "true"
                    )
            );
        }
        // Happy path, validating the inventory and publishing an event.
    }
    // end::exception-details[]

    private boolean criticalSystemError() {
        return false;
    }
    // tag::exception-details[]
}
// end::exception-details[]
