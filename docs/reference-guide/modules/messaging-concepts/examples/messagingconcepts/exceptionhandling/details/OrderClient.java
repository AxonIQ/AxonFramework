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

import messagingconcepts.exceptionhandling.PlaceOrderCommand;

// tag::exception-details-retrieval[]
import org.axonframework.common.TypeReference;
import org.axonframework.messaging.commandhandling.CommandExecutionException;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;

import java.util.Map;
import java.util.Optional;

class OrderClient {

    void placeOrder(CommandGateway commandGateway, PlaceOrderCommand command) {
        try {
            commandGateway.send(command).wait(Void.class);
        } catch (CommandExecutionException e) {
            Optional<Map<String, String>> details = e.getDetails(new TypeReference<Map<String, String>>() {
            });
            details.ifPresent(errorDetails -> {
                String errorCode = errorDetails.get("errorCode");
                boolean retryable = Boolean.parseBoolean(errorDetails.get("retryable"));
                // Decide on retry behavior or user feedback based on errorCode and retryable.
            });
        }
    }
}
// end::exception-details-retrieval[]
