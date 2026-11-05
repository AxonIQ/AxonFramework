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

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.StubLifecycleRegistry;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.monitoring.MessageMonitor;
import org.axonframework.messaging.monitoring.MultiMessageMonitor;
import org.axonframework.messaging.monitoring.configuration.DefaultMessageMonitorRegistry;
import org.axonframework.messaging.monitoring.configuration.MessageMonitorRegistry;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.GenericSubscriptionQueryUpdateMessage;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.SubscriptionQueryUpdateMessage;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link MetricsConfigurationEnhancer}.
 *
 * @author Marijn van Zelst
 * @author Steven van Beelen
 */
class MetricsConfigurationEnhancerTest {

    @Test
    void orderIsAsExpected() {
        MetricsConfigurationEnhancer testSubject = new MetricsConfigurationEnhancer();
        assertThat(testSubject.order()).isEqualTo(MetricsConfigurationEnhancer.ENHANCER_ORDER);
    }

    @Test
    void registersExpectedCommandBusMonitor() {
        Configuration config = buildDefaultConfig();

        MessageMonitorRegistry monitorRegistry = config.getComponent(MessageMonitorRegistry.class);

        MessageMonitor<? super CommandMessage> commandMonitor =
                monitorRegistry.commandMonitor(config, CommandBus.class, null);
        assertThat(commandMonitor).isInstanceOf(MultiMessageMonitor.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<MessageMonitor<? super CommandMessage>> commandMonitors =
                (List<MessageMonitor<? super CommandMessage>>) ((MultiMessageMonitor) commandMonitor).messageMonitors();
        assertThat(commandMonitors).hasExactlyElementsOfTypes(MessageCountingMonitor.class,
                                                              MessageTimerMonitor.class,
                                                              CapacityMonitor.class);
    }

    @Test
    void registersExpectedEventSinkMonitor() {
        Configuration config = buildDefaultConfig();

        MessageMonitorRegistry monitorRegistry = config.getComponent(MessageMonitorRegistry.class);

        MessageMonitor<? super EventMessage> eventMonitor =
                monitorRegistry.eventMonitor(config, EventSink.class, null);
        assertThat(eventMonitor).isInstanceOf(MultiMessageMonitor.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<MessageMonitor<? super EventMessage>> eventMonitors =
                (List<MessageMonitor<? super EventMessage>>) ((MultiMessageMonitor) eventMonitor).messageMonitors();
        assertThat(eventMonitors).hasExactlyElementsOfTypes(MessageCountingMonitor.class, MessageTimerMonitor.class);
    }

    @Test
    void registersExpectedQueryBusMonitor() {
        Configuration config = buildDefaultConfig();

        MessageMonitorRegistry monitorRegistry = config.getComponent(MessageMonitorRegistry.class);

        MessageMonitor<? super QueryMessage> queryMonitor =
                monitorRegistry.queryMonitor(config, QueryBus.class, null);
        assertThat(queryMonitor).isInstanceOf(MultiMessageMonitor.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<MessageMonitor<? super QueryMessage>> queryMonitors =
                (List<MessageMonitor<? super QueryMessage>>) ((MultiMessageMonitor) queryMonitor).messageMonitors();
        assertThat(queryMonitors).hasExactlyElementsOfTypes(MessageCountingMonitor.class,
                                                            MessageTimerMonitor.class,
                                                            CapacityMonitor.class);
    }

    @Test
    void registersExpectedEventProcessorMonitor() {
        Configuration config = buildDefaultConfig();

        MessageMonitorRegistry monitorRegistry = config.getComponent(MessageMonitorRegistry.class);

        MessageMonitor<? super EventMessage> eventMonitor =
                monitorRegistry.eventMonitor(config, EventProcessor.class, "testProcessor");
        assertThat(eventMonitor).isInstanceOf(MultiMessageMonitor.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<MessageMonitor<? super EventMessage>> eventMonitors =
                (List<MessageMonitor<? super EventMessage>>) ((MultiMessageMonitor) eventMonitor).messageMonitors();
        assertThat(eventMonitors).hasExactlyElementsOfTypes(MessageCountingMonitor.class,
                                                            MessageTimerMonitor.class,
                                                            CapacityMonitor.class,
                                                            EventProcessorLatencyMonitor.class);
    }

    @Test
    void registersExpectedSubscriptionQueryUpdateMonitor() {
        Configuration config = buildDefaultConfig();

        MessageMonitorRegistry monitorRegistry = config.getComponent(MessageMonitorRegistry.class);

        MessageMonitor<? super SubscriptionQueryUpdateMessage> queryMonitor =
                monitorRegistry.subscriptionQueryUpdateMonitor(config, QueryBus.class, null);
        assertThat(queryMonitor).isInstanceOf(MultiMessageMonitor.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<MessageMonitor<? super SubscriptionQueryUpdateMessage>> queryMonitors =
                (List<MessageMonitor<? super SubscriptionQueryUpdateMessage>>) ((MultiMessageMonitor) queryMonitor).messageMonitors();
        assertThat(queryMonitors).hasExactlyElementsOfTypes(MessageCountingMonitor.class,
                                                            MessageTimerMonitor.class,
                                                            CapacityMonitor.class);
    }

    @Test
    void registersExpectedCommandBusMonitorWithGivenMeterRegistry() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        Configuration config = buildConfig(meterRegistry);

        MessageMonitorRegistry monitorRegistry = config.getComponent(MessageMonitorRegistry.class);

        MessageMonitor<? super CommandMessage> commandMonitor =
                monitorRegistry.commandMonitor(config, CommandBus.class, null);
        assertThat(commandMonitor).isInstanceOf(MultiMessageMonitor.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<MessageMonitor<? super CommandMessage>> commandMonitors =
                (List<MessageMonitor<? super CommandMessage>>) ((MultiMessageMonitor) commandMonitor).messageMonitors();
        assertThat(commandMonitors).hasExactlyElementsOfTypes(MessageCountingMonitor.class,
                                                              MessageTimerMonitor.class,
                                                              CapacityMonitor.class);

        // Meters are only registered when the first message is ingested by the monitor.
        commandMonitor.onMessageIngested(new GenericCommandMessage(new MessageType("test"), "test"));

        Optional<String> componentName = monitoredComponentName(meterRegistry);
        assertThat(componentName).isPresent();
        assertThat(componentName).contains(CommandBus.class.getSimpleName());
        List<String> uniqueMetricNames = retrieveUniqueMetricNames(meterRegistry);
        assertThat(uniqueMetricNames.size()).isEqualTo(3);
        assertThat(uniqueMetricNames).contains("messageCounter", "messageTimer", "capacity");
    }

    @Test
    void registersExpectedEventSinkMonitorWithGivenMeterRegistry() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        Configuration config = buildConfig(meterRegistry);

        MessageMonitorRegistry monitorRegistry = config.getComponent(MessageMonitorRegistry.class);

        MessageMonitor<? super EventMessage> eventMonitor =
                monitorRegistry.eventMonitor(config, EventSink.class, null);
        assertThat(eventMonitor).isInstanceOf(MultiMessageMonitor.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<MessageMonitor<? super EventMessage>> eventMonitors =
                (List<MessageMonitor<? super EventMessage>>) ((MultiMessageMonitor) eventMonitor).messageMonitors();
        assertThat(eventMonitors).hasExactlyElementsOfTypes(MessageCountingMonitor.class, MessageTimerMonitor.class);

        // Meters are only registered when the first message is ingested by the monitor.
        eventMonitor.onMessageIngested(new GenericEventMessage(new MessageType("test"), "test"));

        Optional<String> componentName = monitoredComponentName(meterRegistry);
        assertThat(componentName).isPresent();
        assertThat(componentName).contains(EventSink.class.getSimpleName());
        List<String> uniqueMetricNames = retrieveUniqueMetricNames(meterRegistry);
        assertThat(uniqueMetricNames.size()).isEqualTo(2);
        assertThat(uniqueMetricNames).contains("messageCounter", "messageTimer");
    }

    @Test
    void registersExpectedQueryBusMonitorWithGivenMeterRegistry() {
        String expectedComponentName = "myQueryBus";
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        Configuration config = buildConfig(meterRegistry);

        MessageMonitorRegistry monitorRegistry = config.getComponent(MessageMonitorRegistry.class);

        MessageMonitor<? super QueryMessage> queryMonitor =
                monitorRegistry.queryMonitor(config, QueryBus.class, expectedComponentName);
        assertThat(queryMonitor).isInstanceOf(MultiMessageMonitor.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<MessageMonitor<? super QueryMessage>> queryMonitors =
                (List<MessageMonitor<? super QueryMessage>>) ((MultiMessageMonitor) queryMonitor).messageMonitors();
        assertThat(queryMonitors).hasExactlyElementsOfTypes(MessageCountingMonitor.class,
                                                            MessageTimerMonitor.class,
                                                            CapacityMonitor.class);

        // Meters are only registered when the first message is ingested by the monitor.
        queryMonitor.onMessageIngested(new GenericQueryMessage(new MessageType("test"), "test"));

        Optional<String> componentName = monitoredComponentName(meterRegistry);
        assertThat(componentName).isPresent();
        assertThat(componentName).contains(expectedComponentName);
        List<String> uniqueMetricNames = retrieveUniqueMetricNames(meterRegistry);
        assertThat(uniqueMetricNames.size()).isEqualTo(3);
        assertThat(uniqueMetricNames).contains("messageCounter", "messageTimer", "capacity");
    }

    @Test
    void registersExpectedEventProcessorMonitorWithGivenMeterRegistry() {
        String expectedComponentName = "testProcessor";
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        Configuration config = buildConfig(meterRegistry);

        MessageMonitorRegistry monitorRegistry = config.getComponent(MessageMonitorRegistry.class);

        MessageMonitor<? super EventMessage> eventMonitor =
                monitorRegistry.eventMonitor(config, EventProcessor.class, expectedComponentName);
        assertThat(eventMonitor).isInstanceOf(MultiMessageMonitor.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<MessageMonitor<? super EventMessage>> eventMonitors =
                (List<MessageMonitor<? super EventMessage>>) ((MultiMessageMonitor) eventMonitor).messageMonitors();
        assertThat(eventMonitors).hasExactlyElementsOfTypes(MessageCountingMonitor.class,
                                                            MessageTimerMonitor.class,
                                                            CapacityMonitor.class,
                                                            EventProcessorLatencyMonitor.class);

        // Meters are only registered when the first message is ingested by the monitor.
        eventMonitor.onMessageIngested(new GenericEventMessage(new MessageType("test"), "test"));

        Optional<String> componentName = monitoredComponentName(meterRegistry);
        assertThat(componentName).isPresent();
        assertThat(componentName).contains(expectedComponentName);
        List<String> uniqueMetricNames = retrieveUniqueMetricNames(meterRegistry);
        assertThat(uniqueMetricNames.size()).isEqualTo(4);
        assertThat(uniqueMetricNames).contains("messageCounter", "messageTimer", "capacity", "latency");
    }

    @Test
    void registersExpectedSubscriptionQueryUpdateMonitorWithGivenMeterRegistry() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        Configuration config = buildConfig(meterRegistry);

        MessageMonitorRegistry monitorRegistry = config.getComponent(MessageMonitorRegistry.class);

        MessageMonitor<? super SubscriptionQueryUpdateMessage> queryMonitor =
                monitorRegistry.subscriptionQueryUpdateMonitor(config, QueryBus.class, null);
        assertThat(queryMonitor).isInstanceOf(MultiMessageMonitor.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<MessageMonitor<? super SubscriptionQueryUpdateMessage>> queryMonitors =
                (List<MessageMonitor<? super SubscriptionQueryUpdateMessage>>) ((MultiMessageMonitor) queryMonitor).messageMonitors();
        assertThat(queryMonitors).hasExactlyElementsOfTypes(MessageCountingMonitor.class,
                                                            MessageTimerMonitor.class,
                                                            CapacityMonitor.class);

        // Meters are only registered when the first message is ingested by the monitor.
        queryMonitor.onMessageIngested(new GenericSubscriptionQueryUpdateMessage(new MessageType("test"), "test"));

        Optional<String> componentName = monitoredComponentName(meterRegistry);
        assertThat(componentName).isPresent();
        assertThat(componentName).contains(QueryBus.class.getSimpleName() + "-emitter");
        List<String> uniqueMetricNames = retrieveUniqueMetricNames(meterRegistry);
        assertThat(uniqueMetricNames.size()).isEqualTo(3);
        assertThat(uniqueMetricNames).contains("messageCounter", "messageTimer", "capacity");
    }

    private Configuration buildDefaultConfig() {
        return buildForEnhancer(new MicrometerMetricsConfigurationEnhancer());
    }

    private Configuration buildConfig(MeterRegistry meterRegistry) {
        return buildForEnhancer(
                registry -> registry.registerComponent(
                        MicrometerMetricsConfiguration.class,
                        c -> new MicrometerMetricsConfiguration(meterRegistry, true)
                )
        );
    }

    private static Configuration buildForEnhancer(ConfigurationEnhancer configurationEnhancer) {
        DefaultComponentRegistry registry = new DefaultComponentRegistry();
        registry.disableEnhancerScanning();
        registry.registerEnhancer(new MetricsConfigurationEnhancer());
        registry.registerEnhancer(configurationEnhancer);
        registry.registerComponent(MessageMonitorRegistry.class, c -> new DefaultMessageMonitorRegistry());
        return registry.build(new StubLifecycleRegistry());
    }

    private static Optional<String> monitoredComponentName(MeterRegistry registry) {
        return registry.getMeters()
                       .stream()
                       .map(Meter::getId)
                       .map(Meter.Id::getName)
                       .findFirst()
                       .map(meterName -> meterName.split("\\.", 3)[0]);
    }

    private static List<String> retrieveUniqueMetricNames(MeterRegistry registry) {
        return registry.getMeters()
                       .stream()
                       .map(Meter::getId)
                       .map(Meter.Id::getName)
                       .map(meterName -> meterName.split("\\.", 3)[1])
                       .distinct()
                       .toList();
    }
}