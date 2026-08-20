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

import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Configuration for the timeout settings of an entire
 * {@link org.axonframework.messaging.core.unitofwork.ProcessingContext}, per bus or event processor.
 * <p>
 * Unlike {@link HandlerTimeoutConfiguration}, which times out an individual message handler invocation, these settings
 * time out the full processing duration of a command bus, a query bus, or an event processor, including any work
 * performed by other interceptors and the commit of the {@code ProcessingContext} itself.
 *
 * @author Steven van Beelen
 * @see UnitOfWorkTimeoutInterceptorBuilder
 * @since 5.4.0
 */
public class UnitOfWorkTimeoutConfiguration {

    /**
     * Timeout settings for the command bus.
     */
    private final TaskTimeoutSettings commandBus;

    /**
     * Timeout settings for the query bus.
     */
    private final TaskTimeoutSettings queryBus;

    /**
     * Timeout settings for event processors that are not present in {@link #eventProcessor}.
     */
    private final TaskTimeoutSettings eventProcessors;

    /**
     * Timeout settings for specific, named event processors, keyed by processor name.
     */
    private final Map<String, TaskTimeoutSettings> eventProcessor;

    /**
     * Creates a new {@code UnitOfWorkTimeoutConfiguration} with default timeout settings. This means all timeouts are
     * disabled.
     */
    public UnitOfWorkTimeoutConfiguration() {
        this(new TaskTimeoutSettings(), new TaskTimeoutSettings(), new TaskTimeoutSettings(), Map.of());
    }

    /**
     * Creates a new {@code UnitOfWorkTimeoutConfiguration} with the given timeout settings.
     *
     * @param commandBus      the timeout settings for the command bus
     * @param queryBus        the timeout settings for the query bus
     * @param eventProcessors the timeout settings for event processors not present in the given {@code eventProcessor}
     *                        map
     * @param eventProcessor  the timeout settings for specific, named event processors, keyed by processor name
     */
    public UnitOfWorkTimeoutConfiguration(TaskTimeoutSettings commandBus,
                                          TaskTimeoutSettings queryBus,
                                          TaskTimeoutSettings eventProcessors,
                                          Map<String, TaskTimeoutSettings> eventProcessor) {
        this.commandBus = commandBus;
        this.queryBus = queryBus;
        this.eventProcessors = eventProcessors;
        this.eventProcessor = eventProcessor;
    }

    /**
     * Retrieves the timeout settings for the command bus.
     *
     * @return the timeout settings for the command bus
     */
    public TaskTimeoutSettings getCommandBus() {
        return commandBus;
    }

    /**
     * Retrieves the timeout settings for the query bus.
     *
     * @return the timeout settings for the query bus
     */
    public TaskTimeoutSettings getQueryBus() {
        return queryBus;
    }

    /**
     * Retrieves the timeout settings for event processors not present in {@link #getEventProcessor()}.
     *
     * @return the default timeout settings for event processors
     */
    public TaskTimeoutSettings getEventProcessors() {
        return eventProcessors;
    }

    /**
     * Retrieves the timeout settings for specific, named event processors, keyed by processor name.
     *
     * @return the timeout settings for specific, named event processors
     */
    public Map<String, TaskTimeoutSettings> getEventProcessor() {
        return eventProcessor;
    }

    /**
     * Retrieves the timeout settings for the event processor with the given {@code processorName}, falling back to
     * {@link #getEventProcessors()} when no specific settings are registered for that name, or when
     * {@code processorName} is {@code null}.
     *
     * @param processorName the name of the event processor to retrieve timeout settings for, may be {@code null}
     * @return the timeout settings for the given {@code processorName}
     */
    public TaskTimeoutSettings eventProcessorSettings(@Nullable String processorName) {
        return processorName == null ? eventProcessors : eventProcessor.getOrDefault(processorName, eventProcessors);
    }
}
