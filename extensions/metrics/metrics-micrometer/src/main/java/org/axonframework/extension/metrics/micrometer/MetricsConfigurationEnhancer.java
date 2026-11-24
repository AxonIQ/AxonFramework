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

package org.axonframework.extension.metrics.micrometer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.axonframework.common.StringUtils;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.SearchScope;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.monitoring.MessageMonitor;
import org.axonframework.messaging.monitoring.MultiMessageMonitor;
import org.axonframework.messaging.monitoring.NoOpMessageMonitor;
import org.axonframework.messaging.monitoring.configuration.MessageMonitorRegistry;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

import static org.axonframework.extension.metrics.micrometer.TagsUtil.*;

/**
 * Implementation of the {@link ConfigurationEnhancer} that uses the {@link MeterRegistry} to decorate several
 * components with {@link MessageMonitor MessageMonitors} with unique {@code MetricRegistries} by registering
 * {@link org.axonframework.messaging.monitoring.configuration.MessageMonitorFactory MessageMonitorFactories} with the
 * {@link MessageMonitorRegistry}.
 * <p>
 * The {@link MeterRegistry} and dimensionality are configured through the {@link MicrometerMetricsConfiguration}
 * component.
 * <p>
 * Components that are decorated are:
 * <ul>
 * <li>Any {@link CommandBus} implementation present in the {@link ComponentRegistry} receives a
 * {@link MessageCountingMonitor), {@link MessageTimerMonitor}, and {@link CapacityMonitor}.</li>
 * <li>Any {@link EventSink} implementation present in the {@link ComponentRegistry} receives a
 * {@code MessageCountingMonitor) and {@code MessageTimerMonitor}.</li>
 * <li>Any {@link EventProcessor} implementation present in the {@link ComponentRegistry} receives a
 * {@code MessageCountingMonitor), {@code MessageTimerMonitor}, {@code CapacityMonitor}, and {@link EventProcessorLatencyMonitor}.</li>
 * <li>Any {@link QueryBus} implementation present in the {@link ComponentRegistry} receives a
 * {@code MessageCountingMonitor), {@code MessageTimerMonitor}, and {@code CapacityMonitor}.</li>
 * </ul>
 * <p>
 * When constructing the {@code MetricsConfigurationEnhancer}, users can choose to add dimensionality to the
 * measurements. When enabled, Micrometer {@link Tag tags} are added by default for the
 * {@link Message#type() message type} {@link MessageType#name() name} and {@link EventProcessor} names, when
 * applicable.
 * <p>
 *
 * @author Steven van Beelen
 * @author Marijn van Zelst
 * @author Ivan Dugalic
 * @since 4.1.0
 */
public class MetricsConfigurationEnhancer implements ConfigurationEnhancer {

    /**
     * The order of {@code this} enhancer, set to {@code -1024}.
     */
    public static final int ENHANCER_ORDER = -1024;

    private final @Nullable MicrometerMetricsConfiguration deprecatedPath;

    /**
     * Initializes a new {@code MetricsConfigurationEnhancer} constructing {@link MessageMonitor MessageMonitors} that
     * are registered with the {@link MeterRegistry} of the {@link MicrometerMetricsConfiguration} component.
     */
    public MetricsConfigurationEnhancer() {
        this.deprecatedPath = null;
    }

    /**
     * Initializes a new {@code MetricsConfigurationEnhancer} constructing {@link MessageMonitor MessageMonitors} that
     * are registered with the given {@code registry}.
     * <p>
     * Will have dimensionality enabled, which will add {@link Tag tags} to measurements.
     *
     * @param registry the {@link MeterRegistry} which will record the metrics
     * @deprecated configure the {@code MetricsConfigurationEnhancer} through the {@link MicrometerMetricsConfiguration}
     * component instead
     */
    @Deprecated(since = "5.5.0")
    public MetricsConfigurationEnhancer(MeterRegistry registry) {
        this.deprecatedPath = new MicrometerMetricsConfiguration(registry, true);
    }

    /**
     * Initializes a new {@code MetricsConfigurationEnhancer} constructing {@link MessageMonitor MessageMonitors} that
     * are registered with the given {@code registry} and {@code useDimensions} toggle.
     *
     * @param registry      the {@link MeterRegistry} which will record the metrics
     * @param useDimensions when set to {@code true} will add {@link Tag tags} to measurements
     * @deprecated configure the {@code MetricsConfigurationEnhancer} through the {@link MicrometerMetricsConfiguration}
     * component instead
     */
    @Deprecated(since = "5.5.0")
    public MetricsConfigurationEnhancer(MeterRegistry registry, boolean useDimensions) {
        this.deprecatedPath = new MicrometerMetricsConfiguration(registry, useDimensions);
    }

    @Override
    public void enhance(ComponentRegistry registry) {
        if (deprecatedPath != null) {
            registry.registerIfNotPresent(MicrometerMetricsConfiguration.class, c -> deprecatedPath, SearchScope.ALL);
        }

        registry.registerDecorator(
                MessageMonitorRegistry.class, 0,
                (config, name, delegate) -> delegate.registerCommandMonitor(
                        (c, componentType, componentName) -> {
                            if (CommandBus.class.isAssignableFrom(componentType)) {
                                MicrometerMetricsConfiguration configuration =
                                        c.getComponent(MicrometerMetricsConfiguration.class);
                                return constructHandlerMonitor(
                                        configuration.registry(),
                                        resolveToMonitorName(componentType, componentName),
                                        configuration.useDimensions()
                                );
                            } else {
                                return NoOpMessageMonitor.instance();
                            }
                        }
                )
        ).registerDecorator(
                MessageMonitorRegistry.class, 0,
                (config, name, delegate) -> delegate.registerEventMonitor(
                        (c, componentType, componentName) -> {
                            if (EventSink.class.isAssignableFrom(componentType)) {
                                MicrometerMetricsConfiguration configuration =
                                        c.getComponent(MicrometerMetricsConfiguration.class);
                                return constructEventSinkMonitors(
                                        configuration.registry(),
                                        resolveToMonitorName(componentType, componentName),
                                        configuration.useDimensions()
                                );
                            } else {
                                return NoOpMessageMonitor.instance();
                            }
                        }
                )
        ).registerDecorator(
                MessageMonitorRegistry.class, 0,
                (config, name, delegate) -> delegate.registerQueryMonitor(
                        (c, componentType, componentName) -> {
                            if (QueryBus.class.isAssignableFrom(componentType)) {
                                MicrometerMetricsConfiguration configuration =
                                        c.getComponent(MicrometerMetricsConfiguration.class);
                                return constructHandlerMonitor(
                                        configuration.registry(),
                                        resolveToMonitorName(componentType, componentName),
                                        configuration.useDimensions());
                            } else {
                                return NoOpMessageMonitor.instance();
                            }
                        }
                )
        ).registerDecorator(
                MessageMonitorRegistry.class, 0,
                (config, name, delegate) -> delegate.registerEventMonitor(
                        (c, componentType, componentName) -> {
                            if (EventProcessor.class.isAssignableFrom(componentType)) {
                                MicrometerMetricsConfiguration configuration =
                                        c.getComponent(MicrometerMetricsConfiguration.class);
                                return constructEventProcessorMonitors(
                                        configuration.registry(),
                                        resolveToMonitorName(componentType, componentName),
                                        configuration.useDimensions()
                                );
                            } else {
                                return NoOpMessageMonitor.instance();
                            }
                        }
                )
        ).registerDecorator(
                MessageMonitorRegistry.class, 0,
                (config, name, delegate) -> delegate.registerSubscriptionQueryUpdateMonitor(
                        (c, componentType, componentName) -> {
                            if (QueryBus.class.isAssignableFrom(componentType)) {
                                MicrometerMetricsConfiguration configuration =
                                        c.getComponent(MicrometerMetricsConfiguration.class);
                                return constructHandlerMonitor(
                                        configuration.registry(),
                                        resolveToMonitorName(componentType, componentName) + "-emitter",
                                        configuration.useDimensions()
                                );
                            } else {
                                return NoOpMessageMonitor.instance();
                            }
                        }
                )
        );
    }

    private MessageMonitor<? super EventMessage> constructEventSinkMonitors(MeterRegistry registry,
                                                                            String sinkName,
                                                                            boolean useDimensions) {
        MessageCountingMonitor countingMonitor = MessageCountingMonitor.buildMonitor(sinkName, registry);
        MessageTimerMonitor timerMonitor = MessageTimerMonitor.builder()
                                                              .meterNamePrefix(sinkName)
                                                              .meterRegistry(registry)
                                                              .tagsBuilder(useDimensions ? MESSAGE_TYPE_TAGGER : NO_OP)
                                                              .build();
        return new MultiMessageMonitor<>(countingMonitor, timerMonitor);
    }

    private MessageMonitor<? super EventMessage> constructEventProcessorMonitors(MeterRegistry registry,
                                                                                 String processorName,
                                                                                 boolean useDimensions) {
        Function<Message, Iterable<Tag>> tagsBuilder = useDimensions ? processorTags(processorName) : NO_OP;
        MessageCountingMonitor countingMonitor =
                MessageCountingMonitor.buildMonitor(processorName, registry, tagsBuilder);
        MessageTimerMonitor timerMonitor = MessageTimerMonitor.builder()
                                                              .meterNamePrefix(processorName)
                                                              .meterRegistry(registry)
                                                              .tagsBuilder(tagsBuilder)
                                                              .build();
        CapacityMonitor capacityMonitor = CapacityMonitor.buildMonitor(processorName, registry, tagsBuilder);
        EventProcessorLatencyMonitor latencyMonitor =
                EventProcessorLatencyMonitor.builder()
                                            .meterNamePrefix(processorName)
                                            .meterRegistry(registry)
                                            .tagsBuilder(useDimensions ? processorNameTag(processorName) : NO_OP)
                                            .build();
        return new MultiMessageMonitor<>(countingMonitor, timerMonitor, capacityMonitor, latencyMonitor);
    }

    private MessageMonitor<Message> constructHandlerMonitor(MeterRegistry registry,
                                                            String name,
                                                            boolean useDimensions) {
        Function<Message, Iterable<Tag>> tagsBuilder = useDimensions ? MESSAGE_TYPE_TAGGER : NO_OP;
        MessageCountingMonitor countingMonitor =
                MessageCountingMonitor.buildMonitor(name, registry, tagsBuilder);
        MessageTimerMonitor timerMonitor = MessageTimerMonitor.builder()
                                                              .meterNamePrefix(name)
                                                              .meterRegistry(registry)
                                                              .tagsBuilder(tagsBuilder)
                                                              .build();
        CapacityMonitor capacityMonitor = CapacityMonitor.buildMonitor(name, registry, tagsBuilder);
        return new MultiMessageMonitor<>(countingMonitor, timerMonitor, capacityMonitor);
    }

    private static String resolveToMonitorName(Class<?> componentType, @Nullable String componentName) {
        return StringUtils.emptyOrNull(componentName) ? componentType.getSimpleName() : componentName;
    }

    @Override
    public int order() {
        return ENHANCER_ORDER;
    }
}
