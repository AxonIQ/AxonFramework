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
package messagingconcepts.processingcontext;

import java.util.concurrent.CompletableFuture;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AccessingContextHandlers {

    private static final Logger logger = LoggerFactory.getLogger(AccessingContextHandlers.class);

    // tag::accessing-context[]
    @CommandHandler
    public OrderResult handle(PlaceOrderCommand command,
                              ProcessingContext context) {
        // Access the context to register callbacks, manage resources, etc.
        context.onCommit(ctx -> {
            logger.info("Order placed successfully");
            return CompletableFuture.completedFuture(null);
        });

        return processOrder(command);
    }

    @EventHandler
    public void on(OrderPlacedEvent event,
                   ProcessingContext context) {
        // Register cleanup action
        context.doFinally(ctx -> releaseResources());

        updateProjection(event);
    }
    // end::accessing-context[]

    private OrderResult processOrder(PlaceOrderCommand command) {
        return new OrderResult();
    }

    private void updateProjection(OrderPlacedEvent event) {
        // Update the read model with the placed order.
    }

    private void releaseResources() {
        // Free any resources acquired during processing.
    }
}
