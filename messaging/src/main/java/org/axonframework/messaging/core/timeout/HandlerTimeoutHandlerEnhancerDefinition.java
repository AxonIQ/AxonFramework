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

package org.axonframework.messaging.core.timeout;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlerTimeout;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.queryhandling.QueryMessage;

/**
 * Inspects message handler and wraps it in a {@link TimeoutWrappedMessageHandlingMember} if the handler should have a
 * timeout.
 * <p>
 * The timeout is determined by the {@link HandlerTimeoutConfiguration} and the {@link MessageHandlerTimeout} annotation
 * on the message handler method. The annotation takes precedence over the configuration.
 *
 * @author Mitchell Herrijgers
 * @see TimeoutWrappedMessageHandlingMember
 * @see HandlerTimeoutConfiguration
 * @since 4.11.0
 */
public class HandlerTimeoutHandlerEnhancerDefinition implements HandlerEnhancerDefinition {

    private final HandlerTimeoutConfiguration configuration;

    /**
     * Creates a new {@link HandlerTimeoutHandlerEnhancerDefinition} with the given configuration. This configuration
     * will be used as default, but can be overridden by the {@link MessageHandlerTimeout} annotation for individual
     * message handlers.
     *
     * @param configuration The configuration for the timeout settings
     */
    public HandlerTimeoutHandlerEnhancerDefinition(HandlerTimeoutConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        TaskTimeoutSettings config = getConfigurationForMember(original);
        if (config == null) {
            // Unknown type of message. Don't enhance the handler.
            return original;
        }

        // We need to calculate the threshold and interval values based on configuration and annotation values.
        int timeout = getAttribute(original, "timeoutMs", config.getTimeoutMs());
        int warning = getAttribute(original, "warningThresholdMs", config.getWarningThresholdMs());
        int warningInterval = getAttribute(original, "warningIntervalMs", config.getWarningIntervalMs());

        if (timeout < 0 && warning < 0) {
            // No timeout configuration found. Don't enhance the handler.
            return original;
        }

        return new TimeoutWrappedMessageHandlingMember<>(original, timeout, warning, warningInterval);
    }

    /**
     * Gets the attribute or the {@link MessageHandlerTimeout} annotation or the default value if the attribute is not
     * present or invalid.
     *
     * @param original The original message handler
     * @param name     The name of the attribute
     * @param fallback The default value
     * @return The attribute value or the default value
     */
    private int getAttribute(MessageHandlingMember<?> original, String name, int fallback) {
        return (int) original.attribute("MessageHandlerTimeout." + name)
                             .filter(i -> ((int) i) >= 0)
                             .orElse(fallback);
    }

    /**
     * Gets the configuration for the given message handler, based on the message type it can handle.
     *
     * @param original The original message handler
     * @return The configuration for the message handler
     */
    private TaskTimeoutSettings getConfigurationForMember(
            MessageHandlingMember<?> original
    ) {
        if (original.canHandleMessageType(EventMessage.class)) {
            return configuration.getEvents();
        }
        if (original.canHandleMessageType(CommandMessage.class)) {
            return configuration.getCommands();
        }
        if (original.canHandleMessageType(QueryMessage.class)) {
            return configuration.getQueries();
        }
        return null;
    }
}
