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

import org.axonframework.axonserver.connector.AxonServerConfigurationEnhancer;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating that the
 * {@link org.axonframework.eventsourcing.configuration.EventSourcingConfigurationDefaults} are registered and
 * customizable when using Spring Boot.
 *
 * @author Steven van Beelen
 */
class EventSourcingConfigurationDefaultsAutoconfigTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withUserConfiguration(TestContext.class)
                .withPropertyValues("axon.axonserver.enabled=false", "axon.eventstorage.jpa.polling-interval=0");
    }

    @Test
    void defaultAxonEventSourcingComponentsArePresent() {
        testContext.run(context -> {
            assertThat(context).hasSingleBean(TagResolver.class);
            assertThat(context).hasBean(TagResolver.class.getName());
            assertThat(context).hasSingleBean(EventStorageEngine.class);
            assertThat(context).hasBean(EventStorageEngine.class.getName());
            assertThat(context).hasSingleBean(EventStore.class);
            assertThat(context).hasSingleBean(EventSink.class);
            assertThat(context).hasBean(EventStore.class.getName());
        });
    }

    @Test
    void overrideDefaultAxonEventSourcingComponentsArePresent() {
        testContext.withUserConfiguration(CustomContext.class).run(context -> {
            assertThat(context).hasSingleBean(TagResolver.class);
            assertThat(context).hasBean("customTagResolver");
            assertThat(context).hasSingleBean(EventStorageEngine.class);
            assertThat(context).hasBean("customEventStorageEngine");
            assertThat(context).hasSingleBean(EventStore.class);
            assertThat(context).hasSingleBean(EventSink.class);
            assertThat(context).hasBean("customEventStore");
        });
    }

    @Configuration
    @EnableAutoConfiguration
    public static class TestContext {

        @Bean
        public ConfigurationEnhancer disableServerConnectorEnhancer() {
            return new ConfigurationEnhancer() {
                @Override
                public void enhance(@NotNull ComponentRegistry registry) {
                    registry.disableEnhancer(AxonServerConfigurationEnhancer.class);
                }

                @Override
                public int order() {
                    return Integer.MIN_VALUE;
                }
            };
        }
    }

    @Configuration
    @EnableAutoConfiguration
    public static class CustomContext {

        @Bean
        public TagResolver customTagResolver() {
            return mock(TagResolver.class);
        }

        @Bean
        public EventStorageEngine customEventStorageEngine() {
            return mock(EventStorageEngine.class);
        }

        @Bean
        public EventStore customEventStore() {
            return mock(EventStore.class);
        }
    }
}
