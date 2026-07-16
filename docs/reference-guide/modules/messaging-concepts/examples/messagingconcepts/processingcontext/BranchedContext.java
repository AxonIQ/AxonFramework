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

import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class BranchedContext {

    private static final ResourceKey<String> CORRELATION_ID_KEY = ResourceKey.withLabel("CorrelationId");

    // tag::branched-context[]
    @EventHandler
    public void on(OrderCreatedEvent event, ProcessingContext context) {
        // Create branched context with additional resource
        ProcessingContext enrichedContext = context.withResource(
            CORRELATION_ID_KEY,
            event.getOrderId()
        );

        // The enriched context has all resources from the original context
        // plus the new correlation ID
        // Lifecycle callbacks registered on either context affect both
    }
    // end::branched-context[]
}
