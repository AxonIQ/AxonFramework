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
package events.eventprocessors.index.interceptors.annotated.surround;

// tag::event-handler-interceptor-surround[]
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.interception.annotation.EventHandlerInterceptor;

// Surround-interceptor: short-circuit events intended for other tenants
public class TenantScopedEventHandler {

    private String tenantId;

    @EventHandlerInterceptor
    MessageStream<?> filterByTenant(
            EventMessage event,
            MessageHandlerInterceptorChain<EventMessage> chain,
            ProcessingContext ctx
    ) {
        if (!tenantId.equals(event.metadata().get("tenantId"))) {
            return MessageStream.empty(); // skip: not our tenant
        }
        return chain.proceed(event, ctx);
    }

    @EventHandler
    void on(OrderPlaced event) { /* ... */ }
}
// end::event-handler-interceptor-surround[]

record OrderPlaced(String orderId) {

}
