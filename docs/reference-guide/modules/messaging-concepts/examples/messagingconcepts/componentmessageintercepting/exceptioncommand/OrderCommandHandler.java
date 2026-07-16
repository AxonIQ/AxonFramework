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
package messagingconcepts.componentmessageintercepting.exceptioncommand;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::exception-command[]
class OrderCommandHandler {
    // end::exception-command[]

    private static final Logger log = LoggerFactory.getLogger(OrderCommandHandler.class);
    // tag::exception-command[]

    // Command handlers omitted

    @ExceptionHandler
    public void handleAll(Exception exception) {
        // Handles all exceptions thrown within this component
    }

    @ExceptionHandler
    public void handleIllegalStateExceptions(IllegalStateException exception) {
        // Handles all IllegalStateExceptions thrown within this component
    }

    @ExceptionHandler(resultType = IllegalStateException.class)
    public void handleIllegalStateExceptions(Exception exception) {
        // Equivalent: handles IllegalStateExceptions using the resultType attribute
    }

    @ExceptionHandler
    public void logFailedCommand(CommandMessage command, Exception exception) {
        // Access the full command message for cross-cutting concerns such as logging
        log.warn("Command {} failed: {}", command.type().qualifiedName(), exception.getMessage());
    }
}
// end::exception-command[]
