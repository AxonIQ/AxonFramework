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
package messagingconcepts.componentmessageintercepting.commandsurround;

import messagingconcepts.componentmessageintercepting.AccessDeniedException;
import messagingconcepts.componentmessageintercepting.PlaceOrderCommand;
import messagingconcepts.componentmessageintercepting.SecurityContext;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

// tag::command-interceptor-surround[]
import org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;

public class OrderCommandHandler {
    // end::command-interceptor-surround[]

    private final SecurityContext securityContext = new SecurityContext();
    // tag::command-interceptor-surround[]

    @CommandHandlerInterceptor
    MessageStream<?> authorize(
            CommandMessage command,
            MessageHandlerInterceptorChain<CommandMessage> chain,
            ProcessingContext context
    ) {
        if (!securityContext.isAuthorized(command)) {
            return MessageStream.failed(new AccessDeniedException("Not authorized"));
        }
        return chain.proceed(command, context);
    }

    @CommandHandler
    void handle(PlaceOrderCommand command, ProcessingContext context) {
        // Handle the command
    }
}
// end::command-interceptor-surround[]
