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

import static java.util.Objects.requireNonNull;

/**
 * Configuration for the timeout settings of an entire
 * {@link org.axonframework.messaging.core.unitofwork.ProcessingContext}, per bus or event processor.
 * <p>
 * Unlike {@link HandlerTimeoutConfiguration}, which times out an individual message handler invocation, these settings
 * time out the full processing duration of a command bus, a query bus, or an event processor, including any work
 * performed by other interceptors and the commit of the {@code ProcessingContext} itself.
 *
 * @author Steven van Beelen
 * @see TimeoutUnitOfWorkFactory
 * @since 5.4.0
 */
public class TimeoutUnitOfWorkFactoryConfiguration {

    /**
     * The {@code TimeoutUnitOfWorkFactoryConfiguration} applied when no other
     * {@code TimeoutUnitOfWorkFactoryConfiguration} component is registered, giving the command bus, query bus, and
     * event processors alike a 60-second timeout, a 10-second warning threshold, and a 1-second warning interval, with
     * no per-processor overrides.
     * <p>
     * These match the defaults Spring Boot auto-configuration applies through {@code TimeoutProperties}, so
     * unit-of-work-level timeout behavior is enabled out of the box regardless of whether an application uses Spring
     * Boot. Construct a {@code TimeoutUnitOfWorkFactoryConfiguration} explicitly, or use
     * {@link #TimeoutUnitOfWorkFactoryConfiguration()} for a fully disabled configuration, to opt out of these
     * defaults.
     */
    public static final TimeoutUnitOfWorkFactoryConfiguration DEFAULT = new TimeoutUnitOfWorkFactoryConfiguration(
            new TaskTimeoutSettings(60_000, 10_000, 1_000),
            new TaskTimeoutSettings(60_000, 10_000, 1_000),
            new TaskTimeoutSettings(60_000, 10_000, 1_000),
            Map.of()
    );

    /**
     * {@link TimeoutUnitOfWorkFactoryConfiguration} that disables
     * {@link org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory}-specific timeout behavior.
     */
    public static final TimeoutUnitOfWorkFactoryConfiguration DISABLED = new TimeoutUnitOfWorkFactoryConfiguration();

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

    private TimeoutUnitOfWorkFactoryConfiguration() {
        this(TaskTimeoutSettings.DISABLED, TaskTimeoutSettings.DISABLED, TaskTimeoutSettings.DISABLED, Map.of());
    }

    /**
     * Creates a new {@code TimeoutUnitOfWorkFactoryConfiguration} with the given timeout settings.
     *
     * @param commandBus      the timeout settings for the command bus
     * @param queryBus        the timeout settings for the query bus
     * @param eventProcessors the timeout settings for event processors not present in the given {@code eventProcessor}
     *                        map
     * @param eventProcessor  the timeout settings for specific, named event processors, keyed by processor name
     */
    public TimeoutUnitOfWorkFactoryConfiguration(TaskTimeoutSettings commandBus,
                                                 TaskTimeoutSettings queryBus,
                                                 TaskTimeoutSettings eventProcessors,
                                                 Map<String, TaskTimeoutSettings> eventProcessor) {
        this.commandBus = requireNonNull(commandBus, "The commandBus timeout properties may not be null.");
        this.queryBus = requireNonNull(queryBus, "The queryBus timeout properties may not be null.");
        this.eventProcessors =
                requireNonNull(eventProcessors, "The eventProcessors timeout properties may not be null.");
        this.eventProcessor = requireNonNull(eventProcessor, "The eventProcessor timeout properties may not be null.");
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
