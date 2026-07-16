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
package messagingconcepts.componentmessageintercepting;

import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;

public class TenantFilterEventInterceptor implements MessageHandlerInterceptor<EventMessage> {

    private final String tenantId;

    public TenantFilterEventInterceptor(String tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public MessageStream<?> interceptOnHandle(EventMessage event,
                                              ProcessingContext context,
                                              MessageHandlerInterceptorChain<EventMessage> chain) {
        if (!tenantId.equals(event.metadata().get("tenantId"))) {
            return MessageStream.empty();
        }
        return chain.proceed(event, context);
    }
}
