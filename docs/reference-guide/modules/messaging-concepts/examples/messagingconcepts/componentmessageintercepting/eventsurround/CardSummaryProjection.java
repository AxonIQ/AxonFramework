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
package messagingconcepts.componentmessageintercepting.eventsurround;

import messagingconcepts.componentmessageintercepting.CardIssuedEvent;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

// tag::event-interceptor-surround[]
import org.axonframework.messaging.eventhandling.interception.annotation.EventHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;

public class CardSummaryProjection {
    // end::event-interceptor-surround[]

    private final String tenantId = "tenant-1";
    // tag::event-interceptor-surround[]

    @EventHandlerInterceptor
    MessageStream<?> filterByTenant(
            EventMessage event,
            MessageHandlerInterceptorChain<EventMessage> chain,
            ProcessingContext context
    ) {
        if (!tenantId.equals(event.metadata().get("tenantId"))) {
            return MessageStream.empty();
        }
        return chain.proceed(event, context);
    }

    @EventHandler
    void on(CardIssuedEvent event, ProcessingContext context) {
        // Handle event
    }
}
// end::event-interceptor-surround[]
