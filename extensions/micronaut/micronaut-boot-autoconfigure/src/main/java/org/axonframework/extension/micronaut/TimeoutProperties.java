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
package org.axonframework.extension.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import org.axonframework.messaging.core.annotation.MessageHandlerTimeout;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.timeout.HandlerTimeoutConfiguration;
import org.axonframework.messaging.core.timeout.TaskTimeoutSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for time limits of processing transactions and handlers in Axon Framework.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@ConfigurationProperties( "axon.timeout")
public class TimeoutProperties {

    /**
     * Whether timeouts are enabled. Defaults to {@code true}. Setting this to false disabled all timeouts, even the
     * ones set through the {@link MessageHandlerTimeout} annotations.
     */
    private boolean enabled = true;

    /**
     * Timeout settings for transactions ({@link UnitOfWork}). Default to
     * 30-second timeout, 10-second warning threshold and a warning interval of 1 second for all types of transactions.
     */
    private TransactionTimeoutProperties transaction = new TransactionTimeoutProperties();

    /**
     * Timeout settings for message handlers. Defaults to 30-second timeout, 10-second warning threshold and a warning
     * interval of 1 second for all types of message handlers.
     */
    private MessageHandlerTimeoutProperties handler = new MessageHandlerTimeoutProperties();

    /**
     * Whether timeouts are enabled. Defaults to {@code true}. Setting this to false disabled all timeouts, even the
     * ones set through the {@link MessageHandlerTimeout} annotations.
     *
     * @return whether timeouts are enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether timeouts are enabled.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Timeout settings for transactions ({@link UnitOfWork}). Default to
     * 30-second timeout, 10-second warning threshold and a warning interval of 1 second for all types of transactions.
     *
     * @return the timeout settings for transactions
     */
    public TransactionTimeoutProperties getTransaction() {
        return transaction;
    }

    /**
     * Sets the timeout settings for transactions.
     *
     * @param properties the timeout settings for transactions
     */
    public void setTransaction(TransactionTimeoutProperties properties) {
        this.transaction = properties;
    }

    /**
     * Timeout settings for message handlers. Defaults to 30-second timeout, 10-second warning threshold and a warning
     * interval of 1 second for all types of message handlers.
     *
     * @return the timeout settings for message handlers
     */
    public MessageHandlerTimeoutProperties getHandler() {
        return handler;
    }

    /**
     * Sets the timeout settings for message handlers.
     *
     * @param properties the timeout settings for message handlers
     */
    public void setHandler(MessageHandlerTimeoutProperties properties) {
        this.handler = properties;
    }

    public static class MessageHandlerTimeoutProperties {

        /**
         * Timeout configuration for event handlers. Defaults to 30-second timeout, 10-second warning threshold and a
         * warning interval of 1 second.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings events = new TaskTimeoutSettings(30000, 10000, 1000);

        /**
         * Timeout configuration for command handlers. Defaults to 30-second timeout, 10-second warning threshold and a
         * warning interval of 1 second.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings commands = new TaskTimeoutSettings(30000, 10000, 1000);

        /**
         * Timeout configuration for query handlers. Defaults to 30-second timeout, 10-second warning threshold and a
         * warning interval of 1 second.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings queries = new TaskTimeoutSettings(30000, 10000, 1000);

        /**
         * Timeout configuration for deadline handlers. Defaults to 30-second timeout, 10-second warning threshold and a
         * warning interval of 1 second.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings deadlines = new TaskTimeoutSettings(30000, 10000, 1000);

        /**
         * The timeout configuration for event handlers. Defaults to 30-second timeout, 10-second warning threshold and
         * a warning interval of 1 second.
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
         * The timeout configuration for command handlers. Defaults to 30-second timeout, 10-second warning threshold
         * and a warning interval of 1 second.
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
         * The timeout configuration for query handlers. Defaults to 30-second timeout, 10-second warning threshold and
         * a warning interval of 1 second.
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
         * The timeout configuration for deadline handlers. Defaults to 30-second timeout, 10-second warning threshold
         * and a warning interval of 1 second.
         *
         * @return the timeout configuration for deadline handlers
         */
        public TaskTimeoutSettings getDeadlines() {
            return deadlines;
        }

        /**
         * Sets the timeout configuration for deadline handlers.
         *
         * @param deadlines the timeout configuration for deadline handlers
         */
        public void setDeadlines(TaskTimeoutSettings deadlines) {
            this.deadlines = deadlines;
        }

        /**
         * Converts this configuration to a {@link HandlerTimeoutConfiguration}.
         *
         * @return the {@link HandlerTimeoutConfiguration} based on this configuration
         */
        public HandlerTimeoutConfiguration toMessageHandlerTimeoutConfiguration() {
            return new HandlerTimeoutConfiguration(events, commands, queries, deadlines);
        }
    }


    public static class TransactionTimeoutProperties {

        /**
         * Timeout settings for the command bus. Defaults to 60-second timeout, 10-second warning threshold and a
         * warning interval of 1 second. This timeout is used for the entire command handling process.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings commandBus = new TaskTimeoutSettings(60000, 10000, 1000);

        /**
         * Timeout settings for the query bus. Defaults to 60-second timeout, 10-second warning threshold and a warning
         * interval of 1 second. This timeout is used for the entire query handling process.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings queryBus = new TaskTimeoutSettings(60000, 10000, 1000);

        /**
         * Timeout settings for deadlines. Defaults to 60-second timeout, 10-second warning threshold and a warning
         * interval of 1 second. This timeout is used for the entire deadline handling process.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings deadline = new TaskTimeoutSettings(60000, 10000, 1000);

        /**
         * Timeout settings for all event processors, unless a more specific setting is registered via the
         * {@code event-processor} property. Defaults to 5-second timeout, 2-second warning threshold and a warning
         * interval of 1 second.
         */
        @NestedConfigurationProperty
        private TaskTimeoutSettings eventProcessors = new TaskTimeoutSettings(60000, 10000, 1000);

        /**
         * Timeout settings for specific event processors. The key is the name of the event processor, the value is the
         * timeout settings for that event processor. Defaults to an empty map.
         */
        private final Map<String, TaskTimeoutSettings> eventProcessor = new HashMap<>();

        /**
         * Timeout settings for the command bus. Defaults to 60-second timeout, 10-second warning threshold and a
         * warning interval of 1 second. This timeout is used for the entire command handling process.
         *
         * @return the timeout settings for the command bus
         */
        public TaskTimeoutSettings getCommandBus() {
            return commandBus;
        }

        /**
         * Sets the timeout settings for the command bus.
         *
         * @param commandBus the timeout settings for the command bus
         */
        public void setCommandBus(TaskTimeoutSettings commandBus) {
            this.commandBus = commandBus;
        }

        /**
         * Timeout settings for the query bus. Defaults to 60-second timeout, 10-second warning threshold and a warning
         * interval of 1 second. This timeout is used for the entire query handling process.
         *
         * @return the timeout settings for the query bus
         */
        public TaskTimeoutSettings getQueryBus() {
            return queryBus;
        }

        /**
         * Sets the timeout settings for the query bus.
         *
         * @param queryBus the timeout settings for the query bus
         */
        public void setQueryBus(TaskTimeoutSettings queryBus) {
            this.queryBus = queryBus;
        }

        /**
         * Timeout settings for deadlines. Defaults to 60-second timeout, 10-second warning threshold and a warning
         * interval of 1 second. This timeout is used for the entire deadline handling process.
         *
         * @return the timeout settings for deadlines
         */
        public TaskTimeoutSettings getDeadline() {
            return deadline;
        }

        /**
         * Sets the timeout settings for deadlines.
         *
         * @param deadline the timeout settings for deadlines
         */
        public void setDeadline(TaskTimeoutSettings deadline) {
            this.deadline = deadline;
        }

        /**
         * Timeout settings for all event processors, unless a more specific setting is registered via the
         * {@code event-processor} property. Defaults to 60-second timeout, 10-second warning threshold and a warning
         * interval of 1 second.
         *
         * @return the timeout settings for event processors
         */
        public TaskTimeoutSettings getEventProcessors() {
            return eventProcessors;
        }

        /**
         * Sets the timeout settings for event processors.
         *
         * @param eventProcessors the timeout settings for event processors
         */
        public void setEventProcessors(TaskTimeoutSettings eventProcessors) {
            this.eventProcessors = eventProcessors;
        }

        /**
         * Timeout settings for specific event processors. The key is the name of the event processor, the value is the
         * timeout settings for that event processor. Defaults to an empty map.
         *
         * @return the timeout settings for specific event processors
         */
        public Map<String, TaskTimeoutSettings> getEventProcessor() {
            return eventProcessor;
        }
    }
}
