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

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.eventsourcing.eventstore.tracing.attributes.EventTagsSpanAttributesProvider;
import org.axonframework.eventsourcing.tracing.configuration.EventSourcingTracingSettings;
import org.axonframework.extension.springboot.TracingProperties;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.tracing.configuration.MessagingTracingSettings;
import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.attributes.AggregateIdentifierSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MessageIdSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MessageTypeSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MetadataSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.SpanAttributesProviderRegistry;
import org.axonframework.modelling.tracing.configuration.ModellingTracingSettings;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests validating the wiring of {@link TracingAutoConfiguration} through Spring Boot's
 * {@link ApplicationContextRunner}.
 * <p>
 * The Spring layer is a thin properties-to-settings translation: assertions therefore run at the framework-component
 * level - the enhancer bean participates in a {@link MessagingConfigurer} build (with ServiceLoader enhancer scanning
 * active, so the framework modules' native tracing enhancers contribute the built-in providers), and the resulting
 * {@link AxonConfiguration} is inspected.
 *
 * @author Mateusz Nowak
 * @since 4.6.0
 */
class TracingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracingAutoConfiguration.class));

    /**
     * Builds an {@link AxonConfiguration} driven by the context's tracing enhancer beans, with ServiceLoader enhancer
     * scanning active so the framework modules' native tracing enhancers (registry defaults, provider contribution,
     * settings defaults) participate exactly as they would in an application.
     */
    private static AxonConfiguration configurationFrom(ApplicationContext context) {
        return MessagingConfigurer.create()
                                  .componentRegistry(registry -> context.getBeansOfType(ConfigurationEnhancer.class)
                                                                        .values()
                                                                        .forEach(e -> e.enhance(registry)))
                                  .build();
    }

    private static List<SpanAttributesProvider> registeredProviders(AxonConfiguration configuration) {
        return configuration.getComponent(SpanAttributesProviderRegistry.class).providers(configuration);
    }

    @Test
    void defaultsContributeTheBuiltInProvidersWithoutASpanFactory() {
        // given / when
        contextRunner.run(context -> {
            AxonConfiguration configuration = configurationFrom(context);

            // then the built-in messaging providers are contributed, and no SpanFactory component exists - a
            // tracing backend (e.g. an OpenTelemetry-backed factory) has to contribute one; decorators degrade to a
            // pass-through
            assertThat(registeredProviders(configuration))
                    .anyMatch(p -> p instanceof MessageIdSpanAttributesProvider)
                    .anyMatch(p -> p instanceof MetadataSpanAttributesProvider)
                    .anyMatch(p -> p instanceof AggregateIdentifierSpanAttributesProvider);
            assertThat(configuration.getOptionalComponent(SpanFactory.class)).isEmpty();
        });
    }

    @Test
    void tracingDisabledContributesNoEnhancerAtAll() {
        // given / when / then - the autoconfiguration backs off entirely
        contextRunner.withPropertyValues("axon.tracing.enabled=false")
                     .run(context -> assertThat(context).doesNotHaveBean("tracingConfigurationEnhancer"));
    }

    @Test
    void commandBusEnabledPropertyBindsToFalse() {
        // given / when / then
        contextRunner.withPropertyValues("axon.tracing.command-bus.enabled=false")
                     .run(context -> {
                         assertThat(context).hasSingleBean(TracingProperties.class);
                         TracingProperties properties = context.getBean(TracingProperties.class);
                         assertThat(properties.getCommandBus().isEnabled()).isFalse();
                     });
    }

    @Test
    void queryBusEnabledPropertyBindsToFalse() {
        // given / when / then
        contextRunner.withPropertyValues("axon.tracing.query-bus.enabled=false")
                     .run(context -> {
                         assertThat(context).hasSingleBean(TracingProperties.class);
                         TracingProperties properties = context.getBean(TracingProperties.class);
                         assertThat(properties.getQueryBus().isEnabled()).isFalse();
                     });
    }

    @Test
    void eventStoreEnabledPropertyReachesTheFrameworkSettingsComponent() {
        // given / when
        contextRunner.withPropertyValues("axon.tracing.event-store.enabled=false")
                     .run(context -> {
                         AxonConfiguration configuration = configurationFrom(context);

                         // then
                         EventSourcingTracingSettings settings =
                                 configuration.getComponent(EventSourcingTracingSettings.class);
                         assertThat(settings.eventStoreEnabled()).isFalse();
                     });
    }

    @Test
    void eventSourcingHandlersEnabledPropertyReachesTheFrameworkSettingsComponent() {
        // given / when the Spring property is set, the settings component must carry it - the component the handler
        // enhancer reads at handle time
        contextRunner.withPropertyValues("axon.tracing.event-sourcing-handlers-enabled=true")
                     .run(context -> {
                         AxonConfiguration configuration = configurationFrom(context);

                         // then
                         MessagingTracingSettings settings =
                                 configuration.getComponent(MessagingTracingSettings.class);
                         assertThat(settings.eventSourcingHandlersEnabled()).isTrue();
                     });
    }

    @Test
    void eventProcessorSubTogglesReachTheFrameworkSettingsComponent() {
        // given / when the event-processor sub-toggles are set, the settings component must carry them - the component
        // the messaging tracing enhancer reads when decorating event-handling components
        contextRunner.withPropertyValues(
                             "axon.tracing.event-processor.batch-trace-enabled=false",
                             "axon.tracing.event-processor.distributed-in-same-trace=true",
                             "axon.tracing.event-processor.distributed-in-same-trace-time-limit=PT5M")
                     .run(context -> {
                         AxonConfiguration configuration = configurationFrom(context);

                         // then
                         MessagingTracingSettings settings =
                                 configuration.getComponent(MessagingTracingSettings.class);
                         assertThat(settings.eventProcessorBatchTraceEnabled()).isFalse();
                         assertThat(settings.eventProcessorDistributedInSameTrace()).isTrue();
                         assertThat(settings.eventProcessorDistributedInSameTraceTimeLimit())
                                 .isEqualTo(Duration.ofMinutes(5));
                     });
    }

    @Test
    void attributeProviderTogglesSuppressTheBuiltInProviders() {
        // given the message-id, metadata and aggregate-identifier providers disabled via properties
        contextRunner.withPropertyValues(
                             "axon.tracing.attribute-providers.message-id=false",
                             "axon.tracing.attribute-providers.metadata=false",
                             "axon.tracing.attribute-providers.aggregate-identifier=false")
                     .run(context -> {
                         AxonConfiguration configuration = configurationFrom(context);

                         // when / then the corresponding providers are not contributed; the rest still are
                         List<SpanAttributesProvider> providers = registeredProviders(configuration);
                         assertThat(providers)
                                 .noneMatch(p -> p instanceof MessageIdSpanAttributesProvider)
                                 .noneMatch(p -> p instanceof MetadataSpanAttributesProvider)
                                 .noneMatch(p -> p instanceof AggregateIdentifierSpanAttributesProvider);
                         assertThat(providers)
                                 .anyMatch(p -> p instanceof MessageTypeSpanAttributesProvider);
                     });
    }

    @Test
    void repositoryAndStateManagerTogglesReachTheModellingSettingsComponent() {
        // given / when the modelling toggles are set, the settings component must carry them - the component the
        // modelling tracing enhancer reads when decorating Repository / StateManager
        contextRunner.withPropertyValues(
                             "axon.tracing.repository.enabled=false",
                             "axon.tracing.state-manager.enabled=false")
                     .run(context -> {
                         AxonConfiguration configuration = configurationFrom(context);

                         // then
                         ModellingTracingSettings settings =
                                 configuration.getComponent(ModellingTracingSettings.class);
                         assertThat(settings.repositoryEnabled()).isFalse();
                         assertThat(settings.stateManagerEnabled()).isFalse();
                     });
    }

    @Test
    void eventTagsProviderIsContributedByDefault() {
        // given the framework-default TagResolver from axon-eventsourcing's configuration defaults
        contextRunner.run(context -> {
            AxonConfiguration configuration = configurationFrom(context);

            // when / then the event-tags provider is contributed to the registry
            assertThat(registeredProviders(configuration))
                    .anyMatch(EventTagsSpanAttributesProvider.class::isInstance);
        });
    }

    @Test
    void eventTagsProviderIsSkippedWhenDisabledViaProperty() {
        // given
        contextRunner.withPropertyValues("axon.tracing.attribute-providers.event-tags=false")
                     .run(context -> {
                         AxonConfiguration configuration = configurationFrom(context);

                         // when / then the toggle reaches the settings component and the provider is not contributed
                         EventSourcingTracingSettings settings =
                                 configuration.getComponent(EventSourcingTracingSettings.class);
                         assertThat(settings.eventTagsEnabled()).isFalse();
                         assertThat(registeredProviders(configuration))
                                 .noneMatch(EventTagsSpanAttributesProvider.class::isInstance);
                     });
    }

    @Test
    void eventSourcingHandlersEnabledDefaultsToFalseAndBindsToTrue() {
        // given / when / then - the default keeps replay-noisy @EventSourcingHandler spans suppressed
        contextRunner.run(context -> {
            TracingProperties properties = context.getBean(TracingProperties.class);
            assertThat(properties.isEventSourcingHandlersEnabled()).isFalse();
        });
        contextRunner.withPropertyValues("axon.tracing.event-sourcing-handlers-enabled=true")
                     .run(context -> {
                         TracingProperties properties = context.getBean(TracingProperties.class);
                         assertThat(properties.isEventSourcingHandlersEnabled()).isTrue();
                     });
    }

    @Test
    void userRegisteredSettingsComponentWinsOverThePropertyTranslation() {
        // given a user-registered MessagingTracingSettings component and a conflicting property
        MessagingTracingSettings userSettings = MessagingTracingSettings.enabledByDefault()
                                                                        .withEventSourcingHandlersEnabled(true);
        contextRunner.withPropertyValues("axon.tracing.event-sourcing-handlers-enabled=false")
                     .run(context -> {
                         AxonConfiguration configuration = MessagingConfigurer.create()
                                 .componentRegistry(registry -> {
                                     registry.registerComponent(MessagingTracingSettings.class, c -> userSettings);
                                     context.getBeansOfType(ConfigurationEnhancer.class).values()
                                            .forEach(e -> e.enhance(registry));
                                 })
                                 .build();

                         // when / then - registerIfNotPresent leaves the user registration in place
                         assertThat(configuration.getComponent(MessagingTracingSettings.class)).isSameAs(userSettings);
                     });
    }

    @Test
    void userDeclaredSpanAttributesProviderBeansAreBridgedIntoTheRegistry() {
        // given a user-declared SpanAttributesProvider bean
        SpanAttributesProvider tenantProvider = new TenantSpanAttributesProvider();
        contextRunner.withBean("tenantProvider", SpanAttributesProvider.class, () -> tenantProvider)
                     .run(context -> {
                         AxonConfiguration configuration = configurationFrom(context);

                         // when / then the bean is contributed to the registry alongside the built-ins
                         assertThat(registeredProviders(configuration)).contains(tenantProvider);
                     });
    }

    /**
     * A user-defined provider, standing in for application-specific span attributes.
     */
    private static final class TenantSpanAttributesProvider implements SpanAttributesProvider {

        @Override
        public Map<String, String> provideForMessage(Message message, @Nullable ProcessingContext context) {
            return Map.of("tenant", "acme");
        }
    }
}
