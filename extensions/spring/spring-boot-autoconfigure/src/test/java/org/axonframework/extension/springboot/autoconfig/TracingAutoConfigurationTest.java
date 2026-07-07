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
import org.axonframework.extension.springboot.TracingProperties;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.tracing.MessagingTracingSettings;
import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.attributes.MessageIdSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MessageTypeSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MetadataSpanAttributesProvider;
import org.axonframework.messaging.tracing.configuration.SpanAttributesProviderRegistry;
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
 * level — the enhancer bean participates in a {@link MessagingConfigurer} build (with ServiceLoader enhancer scanning
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

            // then the built-in messaging providers are contributed, and no SpanFactory component exists — a
            // tracing backend (e.g. an OpenTelemetry-backed factory) has to contribute one; decorators degrade to a
            // pass-through
            assertThat(registeredProviders(configuration))
                    .anyMatch(p -> p instanceof MessageIdSpanAttributesProvider)
                    .anyMatch(p -> p instanceof MetadataSpanAttributesProvider);
            assertThat(configuration.getOptionalComponent(SpanFactory.class)).isEmpty();
        });
    }

    @Test
    void tracingDisabledContributesNoEnhancerAtAll() {
        // given / when / then — the autoconfiguration backs off entirely
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
    void eventProcessorSubTogglesReachTheFrameworkSettingsComponent() {
        // given / when the event-processor sub-toggles are set, the settings component must carry them — the component
        // the messaging tracing enhancer reads when decorating event-handling components
        contextRunner.withPropertyValues(
                             "axon.tracing.event-processor.disable-batch-trace=true",
                             "axon.tracing.event-processor.distributed-in-same-trace=true",
                             "axon.tracing.event-processor.distributed-in-same-trace-time-limit=PT5M")
                     .run(context -> {
                         AxonConfiguration configuration = configurationFrom(context);

                         // then
                         MessagingTracingSettings settings =
                                 configuration.getComponent(MessagingTracingSettings.class);
                         assertThat(settings.eventProcessorDisableBatchTrace()).isTrue();
                         assertThat(settings.eventProcessorDistributedInSameTrace()).isTrue();
                         assertThat(settings.eventProcessorDistributedInSameTraceTimeLimit())
                                 .isEqualTo(Duration.ofMinutes(5));
                     });
    }

    @Test
    void attributeProviderTogglesSuppressTheBuiltInProviders() {
        // given the message-id and metadata providers disabled via properties
        contextRunner.withPropertyValues(
                             "axon.tracing.attribute-providers.message-id=false",
                             "axon.tracing.attribute-providers.metadata=false")
                     .run(context -> {
                         AxonConfiguration configuration = configurationFrom(context);

                         // when / then the corresponding providers are not contributed; the rest still are
                         List<SpanAttributesProvider> providers = registeredProviders(configuration);
                         assertThat(providers)
                                 .noneMatch(p -> p instanceof MessageIdSpanAttributesProvider)
                                 .noneMatch(p -> p instanceof MetadataSpanAttributesProvider);
                         assertThat(providers)
                                 .anyMatch(p -> p instanceof MessageTypeSpanAttributesProvider);
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
