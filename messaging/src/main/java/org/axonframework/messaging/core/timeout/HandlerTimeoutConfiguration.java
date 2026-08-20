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

/**
 * Configuration for the timeout settings of message handlers.
 * <p>
 * Each specific message type can have its own timeout settings.
 *
 * @author Mitchell Herrijgers
 * @since 4.11.0
 */
public class HandlerTimeoutConfiguration {

    /**
     * Timeout settings for event messages.
     */
    private final TaskTimeoutSettings events;

    /**
     * Timeout settings for command messages.
     */
    private final TaskTimeoutSettings commands;

    /**
     * Timeout settings for query messages.
     */
    private final TaskTimeoutSettings queries;

    /**
     * Creates a new {@code HandlerTimeoutConfiguration} with default timeout settings.
     * <p>
     * This means all message handlers have their timeouts disabled.
     */
    public HandlerTimeoutConfiguration() {
        this(new TaskTimeoutSettings(),
             new TaskTimeoutSettings(),
             new TaskTimeoutSettings());
    }

    /**
     * Creates a new {@code HandlerTimeoutConfiguration} with the given timeout settings.
     *
     * @param events   the timeout settings for events
     * @param commands the timeout settings for commands
     * @param queries  the timeout settings for queries
     */
    public HandlerTimeoutConfiguration(TaskTimeoutSettings events,
                                       TaskTimeoutSettings commands,
                                       TaskTimeoutSettings queries) {
        this.events = events;
        this.commands = commands;
        this.queries = queries;
    }

    /**
     * Retrieves the timeout settings for events.
     *
     * @return the timeout settings for events
     */
    public TaskTimeoutSettings getEvents() {
        return events;
    }

    /**
     * Retrieves the timeout settings for commands.
     *
     * @return the timeout settings for commands
     */
    public TaskTimeoutSettings getCommands() {
        return commands;
    }

    /**
     * Retrieves the timeout settings for queries.
     *
     * @return the timeout settings for queries
     */
    public TaskTimeoutSettings getQueries() {
        return queries;
    }
}
