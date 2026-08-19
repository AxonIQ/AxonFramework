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
package messagingconcepts.componentmessageintercepting.commandbefore;

import messagingconcepts.componentmessageintercepting.PlaceOrderCommand;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::command-interceptor-before[]
import org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

public class OrderCommandHandler {
    // end::command-interceptor-before[]

    private static final Logger log = LoggerFactory.getLogger(OrderCommandHandler.class);
    // tag::command-interceptor-before[]

    @CommandHandlerInterceptor
    void logCommand(CommandMessage command) {
        log.info("Handling command: {}", command.type().qualifiedName());
    }

    @CommandHandler
    void handle(PlaceOrderCommand command, ProcessingContext context) {
        // Handle the command
    }
}
// end::command-interceptor-before[]
