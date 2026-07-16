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

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.QueryBus;

class AccessingComponents {

    // tag::accessing-components[]
    @EventHandler
    public void on(OrderPlacedEvent event, ProcessingContext context) {
        // Access framework components
        EventBus eventBus = context.component(EventBus.class);
        CommandGateway gateway = context.component(CommandGateway.class);

        // Access named components
        QueryBus queryBus = context.component(QueryBus.class, "myQueryBus");

        // Use components
        gateway.send(new ProcessOrderCommand(event.getOrderId()));
    }
    // end::accessing-components[]
}
