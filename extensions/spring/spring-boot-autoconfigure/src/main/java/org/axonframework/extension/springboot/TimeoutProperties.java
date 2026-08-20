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
package org.axonframework.extension.springboot;

import org.axonframework.messaging.core.annotation.MessageHandlerTimeout;
import org.axonframework.messaging.core.timeout.HandlerTimeoutConfiguration;
import org.axonframework.messaging.core.timeout.TaskTimeoutSettings;
import org.axonframework.messaging.core.timeout.UnitOfWorkTimeoutConfiguration;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for time limits of processing through the {@link UnitOfWork} and
 * {@link org.axonframework.messaging.core.MessageHandler message handlers} in Axon Framework.
 *
 * @author Mitchell Herrijgers
 * @since 4.11.0
 */
@ConfigurationProperties(prefix = "axon.timeout")
public class TimeoutProperties {

    /**
     * Whether timeouts are enabled. Defaults to {@code true}.
     * <p>
     * Setting this to false disabled all timeouts, even the ones set through the {@link MessageHandlerTimeout}
     * annotations.
     */
    private boolean enabled = true;

    /**
     * Timeout settings for the {@link UnitOfWork}.
     * <p>
     * Default to 30-second timeout, 10-second warning threshold and a warning interval of 1 second for any
     * {@link org.axonframework.messaging.core.Message} types backed by a {@code UnitOfWork}.
     */
    private UnitOfWorkTimeoutProperties unitOfWork = new UnitOfWorkTimeoutProperties();

    /**
     * Timeout settings for {@link org.axonframework.messaging.core.MessageHandler message handlers}.
     * <p>
     * Defaults to 30-second timeout, 10-second warning threshold and a warning interval of 1 second for all types of
     * {@link org.axonframework.messaging.core.MessageHandler message handlers}.
     */
    private MessageHandlerTimeoutProperties handler = new MessageHandlerTimeoutProperties();

    /**
     * Whether timeouts are enabled. Defaults to {@code true}.
     * <p>
     * Setting this to false disabled all timeouts, even the ones set through the {@link MessageHandlerTimeout}
     * annotations.
     *
     * @return whether timeouts are enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether timeouts are enabled.
     *
     * @param enabled provide {@code true} to enable timeout behavior, {@code false} otherwise.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Timeout settings for the {@link UnitOfWork}.
     * <p>
     * Default to 30-second timeout, 10-second warning threshold and a warning interval of 1 second for any
     * {@link org.axonframework.messaging.core.Message} types backed by a {@code UnitOfWork}.
     *
     * @return the timeout settings for the {@link UnitOfWork}
     */
    public UnitOfWorkTimeoutProperties getUnitOfWork() {
        return unitOfWork;
    }

    /**
     * Sets the timeout settings for the {@link UnitOfWork}.
     *
     * @param properties the timeout settings for the {@link UnitOfWork}
     */
    public void setUnitOfWork(UnitOfWorkTimeoutProperties properties) {
        this.unitOfWork = properties;
    }

    /**
     * Timeout settings for {@link org.axonframework.messaging.core.MessageHandler message handlers}.
     * <p>
     * Defaults to 30-second timeout, 10-second warning threshold and a warning interval of 1 second for all types of
     * message handlers.
     *
     * @return the timeout settings for {@link org.axonframework.messaging.core.MessageHandler message handlers}
     */
    public MessageHandlerTimeoutProperties getHandler() {
        return handler;
    }

    /**
     * Sets the timeout settings for {@link org.axonframework.messaging.core.MessageHandler message handlers}.
     *
     * @param properties the timeout settings for
     *                   {@link org.axonframework.messaging.core.MessageHandler message handlers}
     */
    public void setHandler(MessageHandlerTimeoutProperties properties) {
        this.handler = properties;
    }

    /**
     * Timeout properties for {@link org.axonframework.messaging.core.MessageHandler message handlers}.
     */
    public static class MessageHandlerTimeoutProperties {

        /**
         * Timeout configuration for event handlers.
         * <p>
         * Defaults to 30-second timeout, 10-second warning threshold and a warning interval of 1 second.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings events = new TaskTimeoutSettings(30000, 10000, 1000);

        /**
         * Timeout configuration for command handlers.
         * <p>
         * Defaults to 30-second timeout, 10-second warning threshold and a warning interval of 1 second.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings commands = new TaskTimeoutSettings(30000, 10000, 1000);

        /**
         * Timeout configuration for query handlers.
         * <p>
         * Defaults to 30-second timeout, 10-second warning threshold and a warning interval of 1 second.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings queries = new TaskTimeoutSettings(30000, 10000, 1000);

        /**
         * The timeout configuration for event handlers.
         * <p>
         * Defaults to 30-second timeout, 10-second warning threshold and a warning interval of 1 second.
         *
         * @return the timeout configuration for event handlers
         */
        public TaskTimeoutSettings getEvents() {
            return events;
        }

        /**
         * Sets the timeout configuration for event handlers.
         *
         * @param events the timeout configuration for event handlers
         */
        public void setEvents(TaskTimeoutSettings events) {
            this.events = events;
        }

        /**
         * The timeout configuration for command handlers.
         * <p>
         * Defaults to 30-second timeout, 10-second warning threshold and a warning interval of 1 second.
         *
         * @return the timeout configuration for command handlers
         */
        public TaskTimeoutSettings getCommands() {
            return commands;
        }

        /**
         * Sets the timeout configuration for command handlers.
         *
         * @param commands the timeout configuration for command handlers
         */
        public void setCommands(TaskTimeoutSettings commands) {
            this.commands = commands;
        }

        /**
         * The timeout configuration for query handlers.
         * <p>
         * Defaults to 30-second timeout, 10-second warning threshold and a warning interval of 1 second.
         *
         * @return the timeout configuration for query handlers
         */
        public TaskTimeoutSettings getQueries() {
            return queries;
        }

        /**
         * Sets the timeout configuration for query handlers.
         *
         * @param queries the timeout configuration for query handlers
         */
        public void setQueries(TaskTimeoutSettings queries) {
            this.queries = queries;
        }

        /**
         * Converts this configuration to a {@link HandlerTimeoutConfiguration}.
         *
         * @return the {@link HandlerTimeoutConfiguration} based on this configuration
         */
        public HandlerTimeoutConfiguration toMessageHandlerTimeoutConfiguration() {
            return new HandlerTimeoutConfiguration(events, commands, queries);
        }
    }

    /**
     * Timeout properties for the {@link UnitOfWork}.
     */
    public static class UnitOfWorkTimeoutProperties {

        /**
         * Timeout settings for the {@link UnitOfWork} constructed by the
         * {@link org.axonframework.messaging.commandhandling.CommandBus}.
         * <p>
         * Defaults to 60-second timeout, 10-second warning threshold and a warning interval of 1 second. This timeout
         * is used for the entire command handling process.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings commandBus = new TaskTimeoutSettings(60000, 10000, 1000);

        /**
         * Timeout settings for the {@link UnitOfWork} constructed by the
         * {@link org.axonframework.messaging.queryhandling.QueryBus}.
         * <p>
         * Defaults to 60-second timeout, 10-second warning threshold and a warning interval of 1 second. This timeout
         * is used for the entire query handling process.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings queryBus = new TaskTimeoutSettings(60000, 10000, 1000);

        /**
         * Timeout settings for the {@link UnitOfWork} constructed by any
         * {@link org.axonframework.messaging.eventhandling.processing.EventProcessor}, unless a more specific setting
         * is registered via the {@code event-processor} property.
         * <p>
         * Defaults to 5-second timeout, 2-second warning threshold and a warning interval of 1 second.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings eventProcessors = new TaskTimeoutSettings(60000, 10000, 1000);

        /**
         * Timeout settings for the {@link UnitOfWork} constructed by a specific
         * {@link org.axonframework.messaging.eventhandling.processing.EventProcessor EventProcessors}.
         * <p>
         * The key is the name of the event processor, the value is the timeout settings for that event processor.
         * Defaults to an empty map.
         */
        private final Map<String, TaskTimeoutSettings> eventProcessor = new HashMap<>();

        /**
         * Timeout settings for the {@link UnitOfWork} constructed by the
         * {@link org.axonframework.messaging.commandhandling.CommandBus}.
         * <p>
         * Defaults to 60-second timeout, 10-second warning threshold and a warning interval of 1 second. This timeout
         * is used for the entire command handling process.
         *
         * @return the timeout settings the {@link UnitOfWork} constructed by the
         * {@link org.axonframework.messaging.commandhandling.CommandBus}
         */
        public TaskTimeoutSettings getCommandBus() {
            return commandBus;
        }

        /**
         * Sets the timeout settings of the {@link UnitOfWork} constructed by the
         * {@link org.axonframework.messaging.commandhandling.CommandBus}.
         *
         * @param commandBus the timeout settings of the {@link UnitOfWork} constructed by the
         *                   {@link org.axonframework.messaging.commandhandling.CommandBus}.
         */
        public void setCommandBus(TaskTimeoutSettings commandBus) {
            this.commandBus = commandBus;
        }

        /**
         * Timeout settings for the {@link UnitOfWork} constructed by the
         * {@link org.axonframework.messaging.queryhandling.QueryBus}.
         * <p>
         * Defaults to 60-second timeout, 10-second warning threshold and a warning interval of 1 second. This timeout
         * is used for the entire query handling process.
         *
         * @return the timeout settings for the {@link UnitOfWork} constructed by the
         * {@link org.axonframework.messaging.queryhandling.QueryBus}
         */
        public TaskTimeoutSettings getQueryBus() {
            return queryBus;
        }

        /**
         * Sets the timeout settings of the {@link UnitOfWork} constructed by the
         * {@link org.axonframework.messaging.queryhandling.QueryBus}.
         *
         * @param queryBus the timeout settings of the {@link UnitOfWork} constructed by the
         *                 {@link org.axonframework.messaging.queryhandling.QueryBus}.
         */
        public void setQueryBus(TaskTimeoutSettings queryBus) {
            this.queryBus = queryBus;
        }

        /**
         * Timeout settings for the {@link UnitOfWork} constructed by any
         * {@link org.axonframework.messaging.eventhandling.processing.EventProcessor}, unless a more specific setting
         * is registered via the {@code event-processor} property.
         * <p>
         * Defaults to 60-second timeout, 10-second warning threshold and a warning interval of 1 second.
         *
         * @return the timeout settings for the {@link UnitOfWork} constructed by any
         * {@link org.axonframework.messaging.eventhandling.processing.EventProcessor}
         */
        public TaskTimeoutSettings getEventProcessors() {
            return eventProcessors;
        }

        /**
         * Sets the timeout settings for the {@link UnitOfWork} constructed by any
         * {@link org.axonframework.messaging.eventhandling.processing.EventProcessor}
         *
         * @param eventProcessors the timeout settings for the {@link UnitOfWork} constructed by any
         *                        {@link org.axonframework.messaging.eventhandling.processing.EventProcessor}
         */
        public void setEventProcessors(TaskTimeoutSettings eventProcessors) {
            this.eventProcessors = eventProcessors;
        }

        /**
         * Timeout settings for the {@link UnitOfWork} constructed by a specific
         * {@link org.axonframework.messaging.eventhandling.processing.EventProcessor EventProcessors}.
         * <p>
         * The key is the name of the event processor, the value is the timeout settings for that event processor.
         * Defaults to an empty map.
         *
         * @return the timeout settings for the {@link UnitOfWork} constructed by a specific
         * {@link org.axonframework.messaging.eventhandling.processing.EventProcessor EventProcessors}.
         */
        public Map<String, TaskTimeoutSettings> getEventProcessor() {
            return eventProcessor;
        }

        /**
         * Converts this configuration to a {@link UnitOfWorkTimeoutConfiguration}.
         *
         * @return the {@link UnitOfWorkTimeoutConfiguration} based on this configuration
         */
        public UnitOfWorkTimeoutConfiguration toUnitOfWorkTimeoutConfiguration() {
            return new UnitOfWorkTimeoutConfiguration(commandBus, queryBus, eventProcessors, eventProcessor);
        }
    }
}
