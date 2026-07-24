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
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;

// tag::custom-phase[]
class CustomPhaseInterceptor implements MessageHandlerInterceptor<Message> {

    @Override
    public MessageStream<?> interceptOnHandle(
            Message message,
            ProcessingContext context,
            MessageHandlerInterceptorChain<Message> chain
    ) {
        // Order between PRE_INVOCATION (order = -10000) and INVOCATION (order = 0) phase.
        ProcessingLifecycle.Phase customPhase = () -> -5000;

        context.on(customPhase, ctx -> {
            // Custom logic here
            performCustomSetup();
            return CompletableFuture.completedFuture(null);
        });
        return chain.proceed(message, context);
    }
    // end::custom-phase[]

    private void performCustomSetup() {
        // Prepare resources at the custom point in the lifecycle.
    }
    // tag::custom-phase[]
}
// end::custom-phase[]
