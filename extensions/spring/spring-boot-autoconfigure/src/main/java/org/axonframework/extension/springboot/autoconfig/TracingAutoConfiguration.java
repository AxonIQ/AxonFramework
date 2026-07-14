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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.eventsourcing.tracing.EventSourcingTracingSettings;
import org.axonframework.extension.springboot.TracingProperties;
import org.axonframework.messaging.tracing.MessagingTracingSettings;
import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.configuration.SpanAttributesProviderRegistry;
import org.axonframework.modelling.tracing.ModellingTracingSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration translating the {@code axon.tracing.*} properties into the framework's native tracing
 * configuration.
 * <p>
 * This layer is deliberately thin: it registers the {@code *TracingSettings} components derived from
 * {@link TracingProperties} (via {@code registerIfNotPresent}, so a user-defined settings bean or component always
 * wins) and bridges user-declared {@link SpanAttributesProvider} beans into the
 * {@link SpanAttributesProviderRegistry}. Everything else - the built-in attribute providers, their toggles, the
 * tracing decorators - is wired natively by the ServiceLoader-discovered enhancers of the framework modules, driven
 * by these settings.
 * <p>
 * The {@link SpanFactory} is <b>optional</b> and contributed by a tracing backend (for example an
 * OpenTelemetry-backed {@code SpanFactory} registered by its own autoconfiguration). When no factory component is
 * present, the tracing decorators leave every component undecorated - tracing is off with zero overhead. The whole
 * configuration backs off when {@code axon.tracing.enabled} is set to {@code false}, in which case no settings are
 * registered.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
@AutoConfiguration
@ConditionalOnClass(SpanFactory.class)
@ConditionalOnProperty(prefix = "axon.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TracingProperties.class)
public class TracingAutoConfiguration {

    /**
     * Constructs a {@link ConfigurationEnhancer} translating the {@code axon.tracing.*} properties into the tracing
     * settings components and bridging user-declared {@link SpanAttributesProvider} beans into the
     * {@link SpanAttributesProviderRegistry}.
     * <p>
     * Settings are registered with {@code registerIfNotPresent}: a user-defined settings bean (or an explicitly
     * registered component) takes precedence over the property translation, which in turn takes precedence over the
     * framework modules' native {@code enabledByDefault()} defaults (registered by enhancers running at maximal
     * order).
     *
     * @param customProviders the user-declared {@link SpanAttributesProvider} beans to contribute to the registry
     * @param properties      the bound {@link TracingProperties}
     * @return a {@link ConfigurationEnhancer} registering the tracing settings with the framework
     */
    @Bean
    public ConfigurationEnhancer tracingConfigurationEnhancer(ObjectProvider<SpanAttributesProvider> customProviders,
                                                              TracingProperties properties) {
        return registry -> {
            TracingProperties.AttributeProviders providers = properties.getAttributeProviders();
            registry.registerIfNotPresent(
                    MessagingTracingSettings.class,
                    c -> new MessagingTracingSettings(
                            properties.getCommandBus().isEnabled(),
                            properties.getEventSink().isEnabled(),
                            properties.getEventProcessor().isEnabled(),
                            properties.getEventProcessor().isDisableBatchTrace(),
                            properties.getEventProcessor().isDistributedInSameTrace(),
                            properties.getEventProcessor().getDistributedInSameTraceTimeLimit(),
                            properties.getQueryBus().isEnabled(),
                            properties.isEventSourcingHandlersEnabled(),
                            new MessagingTracingSettings.SpanAttributesProviders(
                                    providers.isMessageId(),
                                    providers.isMessageType(),
                                    providers.isMetadata()))
            );
            registry.registerIfNotPresent(
                    ModellingTracingSettings.class,
                    c -> new ModellingTracingSettings(
                            properties.getRepository().isEnabled(),
                            properties.getStateManager().isEnabled(),
                            new ModellingTracingSettings.SpanAttributesProviders(providers.isAggregateIdentifier()))
            );
            registry.registerIfNotPresent(
                    EventSourcingTracingSettings.class,
                    c -> new EventSourcingTracingSettings(
                            properties.getEventStore().isEnabled(),
                            properties.getSnapshotStore().isEnabled(),
                            new EventSourcingTracingSettings.SpanAttributesProviders(providers.isEventTags()))
            );
            // Bridge user-declared SpanAttributesProvider beans into the registry. The built-in providers are not
            // beans (they are contributed natively per the settings above), so the ObjectProvider yields only
            // application-defined providers.
            registry.registerDecorator(
                    SpanAttributesProviderRegistry.class,
                    0,
                    (config, name, delegate) -> {
                        customProviders.orderedStream().forEach(provider -> delegate.registerProvider(c -> provider));
                        return delegate;
                    }
            );
        };
    }
}
