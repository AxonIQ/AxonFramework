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
package messagingconcepts.componentmessageintercepting.exceptionprojection;

import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::exception-projection[]
class CardSummaryProjection {
    // end::exception-projection[]

    private static final Logger log = LoggerFactory.getLogger(CardSummaryProjection.class);
    // tag::exception-projection[]

    // Event handlers and query handlers omitted

    @ExceptionHandler
    public void handleAll(Exception exception) {
        // Handles all exceptions thrown within this component
    }

    @ExceptionHandler
    public void handleIllegalArgumentExceptions(IllegalArgumentException exception) {
        // Handles all IllegalArgumentExceptions within this component
    }

    @ExceptionHandler(resultType = IllegalArgumentException.class)
    public void handleIllegalArgumentExceptions(Exception exception) {
        // Equivalent: handles IllegalArgumentExceptions using the resultType attribute
    }

    @ExceptionHandler
    public void logFailedEvent(EventMessage event, Exception exception) {
        // Access the full event message for cross-cutting concerns such as logging
        log.warn("Event {} failed: {}", event.type().qualifiedName(), exception.getMessage());
    }
}
// end::exception-projection[]
