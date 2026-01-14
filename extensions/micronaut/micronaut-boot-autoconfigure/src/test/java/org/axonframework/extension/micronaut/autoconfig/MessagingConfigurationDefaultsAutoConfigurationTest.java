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

package org.axonframework.extension.micronaut.autoconfig;

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.RoutingStrategy;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.configuration.MessagingConfigurationDefaults;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurationDefaults;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.conversion.Converter;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating that the {@link MessagingConfigurationDefaults} are registered
 * and customizable when using Spring Boot.
 *
 * @author Steven van Beelen
 */
class MessagingConfigurationDefaultsAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class)
                .withPropertyValues("axon.axonserver.enabled=false", "axon.eventstorage.jpa.polling-interval=0");
    }

    @Test
    void defaultAxonMessagingComponentsArePresent() {
        testContext.run(context -> {
            assertThat(context).hasSingleBean(MessageTypeResolver.class);
            assertThat(context).hasBean(MessageTypeResolver.class.getName());
            // The Converter, MessageConverter, and EventConverter all are Converter implementations, hence three.
            Map<String, Converter> beansOfType = context.getBeansOfType(Converter.class);
            assertThat(beansOfType.size()).isEqualTo(3);
            assertThat(context).hasBean(Converter.class.getName());
            assertThat(context).hasSingleBean(MessageConverter.class);
            assertThat(context).hasBean(MessageConverter.class.getName());
            assertThat(context).hasSingleBean(EventConverter.class);
            assertThat(context).hasBean(EventConverter.class.getName());
            assertThat(context).hasSingleBean(CommandGateway.class);
            assertThat(context).hasBean(CommandGateway.class.getName());
            assertThat(context).hasSingleBean(CommandBus.class);
            assertThat(context).hasBean(CommandBus.class.getName());
            assertThat(context).hasSingleBean(RoutingStrategy.class);
            assertThat(context).hasBean(RoutingStrategy.class.getName());
            assertThat(context).hasSingleBean(EventGateway.class);
            assertThat(context).hasBean(EventGateway.class.getName());
            assertThat(context).hasSingleBean(EventSink.class);
            assertThat(context).hasSingleBean(EventBus.class);
            assertThat(context).hasBean(EventBus.class.getName());
            assertThat(context).hasSingleBean(QueryGateway.class);
            assertThat(context).hasBean(QueryGateway.class.getName());
            assertThat(context).hasSingleBean(QueryBus.class);
            assertThat(context).hasBean(QueryBus.class.getName());
        });
    }

    @Test
    void overrideDefaultAxonMessagingComponents() {
        testContext.withUserConfiguration(CustomContext.class).run(context -> {
            assertThat(context).hasSingleBean(MessageTypeResolver.class);
            assertThat(context).hasBean("customMessageTypeResolver");
            // The Converter, MessageConverter, and EventConverter all are Converter implementations, hence three.
            assertThat(context.getBeansOfType(Converter.class).size()).isEqualTo(3);
            assertThat(context).hasBean("customConverter");
            assertThat(context).hasSingleBean(MessageConverter.class);
            assertThat(context).hasBean("customMessageConverter");
            assertThat(context).hasSingleBean(EventConverter.class);
            assertThat(context).hasBean("customEventConverter");
            assertThat(context).hasSingleBean(CommandGateway.class);
            assertThat(context).hasBean("customCommandGateway");
            assertThat(context).hasSingleBean(CommandBus.class);
            assertThat(context).hasBean("customCommandBus");
            assertThat(context).hasSingleBean(RoutingStrategy.class);
            assertThat(context).hasBean("customRoutingStrategy");
            assertThat(context).hasSingleBean(EventGateway.class);
            assertThat(context).hasBean("customEventGateway");
            assertThat(context).hasSingleBean(EventSink.class);
            assertThat(context).hasSingleBean(EventBus.class);
            assertThat(context).hasBean("customEventBus");
            assertThat(context).hasSingleBean(QueryGateway.class);
            assertThat(context).hasBean("customQueryGateway");
            assertThat(context).hasSingleBean(QueryBus.class);
            assertThat(context).hasBean("customQueryBus");
        });
    }

    // Excludes the ConverterAutoConfiguration to validate the MessagingConfigurationDefaults on its own.
    @Configuration
    @EnableAutoConfiguration(
            exclude = ConverterAutoConfiguration.class
    )
    public static class TestContext {

        @Bean
        public ConfigurationEnhancer disableEventSourcingConfigurationDefaults() {
            return new ConfigurationEnhancer() {

                @Override
                public void enhance(@NotNull ComponentRegistry registry) {
                    registry.disableEnhancer(EventSourcingConfigurationDefaults.class);
                }

                @Override
                public int order() {
                    return Integer.MIN_VALUE;
                }
            };
        }
    }

    // Excludes the ConverterAutoConfiguration to validate the MessagingConfigurationDefaults on its own.
    @Configuration
    @EnableAutoConfiguration(
            exclude = ConverterAutoConfiguration.class
    )
    public static class CustomContext {

        @Bean
        public MessageTypeResolver customMessageTypeResolver() {
            return mock(MessageTypeResolver.class);
        }

        @Bean
        public Converter customConverter() {
            return mock(Converter.class);
        }

        @Bean
        public MessageConverter customMessageConverter() {
            return mock(MessageConverter.class);
        }

        @Bean
        public EventConverter customEventConverter() {
            return mock(EventConverter.class);
        }

        @Bean
        public CommandGateway customCommandGateway() {
            return mock(CommandGateway.class);
        }

        @Bean
        public CommandBus customCommandBus() {
            return mock(CommandBus.class);
        }

        @Bean
        public RoutingStrategy customRoutingStrategy() {
            return mock(RoutingStrategy.class);
        }

        @Bean
        public EventGateway customEventGateway() {
            return mock(EventGateway.class);
        }

        @Bean
        public EventBus customEventBus() {
            return mock(EventBus.class);
        }

        @Bean
        public QueryGateway customQueryGateway() {
            return mock(QueryGateway.class);
        }

        @Bean
        public QueryBus customQueryBus() {
            return mock(QueryBus.class);
        }
    }
}
