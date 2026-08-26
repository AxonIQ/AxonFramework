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

package org.axonframework.messaging.eventhandling.processing.errorhandling;

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of a {@link ListenerInvocationErrorHandler} that logs exceptions as errors but otherwise does nothing
 * to prevent event handling from continuing.
 *
 * @author Rene de Waele
 */
public class LoggingErrorHandler implements ListenerInvocationErrorHandler {

    private final Logger logger;

    /**
     * Initialize the LoggingErrorHandler using the logger for "org.axonframework.messaging.errorhandling.processing.eventhandling.LoggingErrorHandler".
     */
    public LoggingErrorHandler() {
        this(LoggerFactory.getLogger(LoggingErrorHandler.class));
    }

    /**
     * Initialize the LoggingErrorHandler to use the given {@code logger} to log errors
     *
     * @param logger the logger to log errors with
     */
    public LoggingErrorHandler(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onError(Exception exception, EventMessage event,
                        EventMessageHandler eventHandler) {
        logger.error("EventListener [{}] failed to handle event [{}] ({}). " +
                             "Continuing processing with next listener",
                     eventHandler.getTargetType().getSimpleName(),
                     event.identifier(),
                     event.type().name(),
                     exception);
    }
}
