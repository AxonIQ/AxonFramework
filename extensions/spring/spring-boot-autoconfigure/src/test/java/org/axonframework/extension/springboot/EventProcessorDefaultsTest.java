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

package org.axonframework.extension.springboot;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.extension.spring.config.EventProcessorSettings;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorModule;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating how the global defaults under {@code axon.eventhandling.defaults} are merged into the
 * {@link EventProcessorProperties.ProcessorSettings} of individual processors.
 *
 * @author Steven van Beelen
 */
class EventProcessorDefaultsTest {

    private static final String DEFAULTS = "axon.eventhandling.defaults.";
    private static final String PROCESSOR = "axon.eventhandling.processors[foo].";

    @Nested
    class PooledDefaults {

        @Test
        void processorWithoutOwnPropertiesStartsFromThePooledDefaults() {
            // given
            Environment environment = environmentWith(DEFAULTS + "pooled.batch-size", "10",
                                                      DEFAULTS + "pooled.thread-count", "6",
                                                      PROCESSOR + "initial-segment-count", "2");

            // when
            var settings = pooled(EventProcessorProperties.getProcessors(environment).get("foo"));

            // then
            assertThat(settings.batchSize()).isEqualTo(10);
            assertThat(settings.threadCount()).isEqualTo(6);
            assertThat(settings.initialSegmentCount()).isEqualTo(2);
        }

        @Test
        void explicitlySetValueOverridesTheDefaultEvenWhenEqualToThePropertyDefault() {
            // given - the processor explicitly sets the built-in default of 1, against a global default of 10
            Environment environment = environmentWith(DEFAULTS + "pooled.batch-size", "10",
                                                      PROCESSOR + "batch-size", "1");

            // when
            var settings = pooled(EventProcessorProperties.getProcessors(environment).get("foo"));

            // then
            assertThat(settings.batchSize()).isEqualTo(1);
        }

        @Test
        void defaultsOfOneProcessorDoNotLeakIntoAnother() {
            // given
            Environment environment = environmentWith(DEFAULTS + "pooled.batch-size", "10",
                                                      PROCESSOR + "batch-size", "3",
                                                      "axon.eventhandling.processors[bar].thread-count", "2");

            // when
            var processors = EventProcessorProperties.getProcessors(environment);

            // then
            assertThat(pooled(processors.get("foo")).batchSize()).isEqualTo(3);
            assertThat(pooled(processors.get("bar")).batchSize()).isEqualTo(10);
        }

        @Test
        void processorsWithoutSettingsFallBackToThePooledDefaults() {
            // given
            Environment environment = environmentWith(DEFAULTS + "pooled.batch-size", "10");

            // when
            var settings = EventProcessorProperties.getDefaultProcessor(environment);

            // then
            assertThat(settings.getMode()).isEqualTo(EventProcessorProperties.Mode.POOLED);
            assertThat(settings.batchSize()).isEqualTo(10);
        }

        @Test
        void noDefaultsKeepsTheBuiltInValues() {
            // given
            Environment environment = environmentWith(PROCESSOR + "mode", "pooled");

            // when
            var settings = pooled(EventProcessorProperties.getProcessors(environment).get("foo"));

            // then
            var builtIn = new EventProcessorProperties.ProcessorSettings();
            assertThat(settings.batchSize()).isEqualTo(builtIn.batchSize());
            assertThat(settings.threadCount()).isEqualTo(builtIn.threadCount());
            assertThat(settings.initialToken()).isEqualTo(EventProcessorSettings.InitialToken.DEFAULT);
        }
    }

    @Nested
    class SubscribingDefaults {

        @Test
        void subscribingProcessorStartsFromTheSubscribingDefaultsOnly() {
            // given
            Environment environment = environmentWith(DEFAULTS + "pooled.batch-size", "10",
                                                      DEFAULTS + "subscribing.source", "my-source",
                                                      PROCESSOR + "mode", "subscribing");

            // when
            var settings = EventProcessorProperties.getProcessors(environment).get("foo");

            // then
            assertThat(settings.processorMode()).isEqualTo(EventProcessorSettings.ProcessorMode.SUBSCRIBING);
            assertThat(settings.source()).isEqualTo("my-source");
            assertThat(((EventProcessorProperties.ProcessorSettings) settings).batchSize())
                    .isEqualTo(new EventProcessorProperties.ProcessorSettings().batchSize());
        }

        @Test
        void aModeInTheDefaultsDoesNotChangeTheModeOfAProcessor() {
            // given
            Environment environment = environmentWith(DEFAULTS + "pooled.mode", "subscribing",
                                                      PROCESSOR + "batch-size", "2");

            // when
            var settings = EventProcessorProperties.getProcessors(environment).get("foo");

            // then
            assertThat(settings.processorMode()).isEqualTo(EventProcessorSettings.ProcessorMode.POOLED);
        }

        @Test
        void startsAnApplicationApplyingTheGlobalSubscribingCustomizationBean() {
            // given - a subscribing processor and a global customization bean
            new ApplicationContextRunner()
                    .withUserConfiguration(TestContext.class, GlobalSubscribingCustomizationContext.class)
                    .withPropertyValues("axon.eventhandling.processors[..default].mode=subscribing")
                    // when / then
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        context.getBean(AxonConfiguration.class);
                        assertThat(context.getBean(AtomicInteger.class)).hasValueGreaterThanOrEqualTo(1);
                    });
        }
    }

    @Nested
    class InitialTokenProperties {

        @Test
        void bindsTheInitialTokenAndItsTimestamp() {
            // given
            Environment environment = environmentWith(PROCESSOR + "initial-token", "at_timestamp",
                                                      PROCESSOR + "initial-token-timestamp", "2026-01-01T00:00:00Z");

            // when
            var settings = pooled(EventProcessorProperties.getProcessors(environment).get("foo"));

            // then
            assertThat(settings.initialToken()).isEqualTo(EventProcessorSettings.InitialToken.AT_TIMESTAMP);
            assertThat(settings.initialTokenTimestamp()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        }

        @Test
        void initialTokenCanBeSetAsAGlobalDefault() {
            // given
            Environment environment = environmentWith(DEFAULTS + "pooled.initial-token", "latest",
                                                      PROCESSOR + "batch-size", "2");

            // when
            var settings = pooled(EventProcessorProperties.getProcessors(environment).get("foo"));

            // then
            assertThat(settings.initialToken()).isEqualTo(EventProcessorSettings.InitialToken.LATEST);
        }

        @Test
        void failsToStartAnApplicationWithAtTimestampWithoutATimestamp() {
            // given / when / then
            new ApplicationContextRunner()
                    .withUserConfiguration(TestContext.class)
                    .withPropertyValues(DEFAULTS + "pooled.initial-token=at_timestamp")
                    .run(context -> assertThat(context)
                            .getFailure()
                            .hasStackTraceContaining("initial-token-timestamp"));
        }

        @Test
        void startsAnApplicationWithTheHeadAsInitialToken() {
            // given / when / then
            new ApplicationContextRunner()
                    .withUserConfiguration(TestContext.class)
                    .withPropertyValues(DEFAULTS + "pooled.initial-token=latest")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    private static EventProcessorSettings.PooledEventProcessorSettings pooled(EventProcessorSettings settings) {
        assertThat(settings).isInstanceOf(EventProcessorSettings.PooledEventProcessorSettings.class);
        return (EventProcessorSettings.PooledEventProcessorSettings) settings;
    }

    private static Environment environmentWith(String... keysAndValues) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            environment.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return environment;
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestContext {

        @Bean
        public TokenStore tokenStore() {
            return new InMemoryTokenStore();
        }

        @Component
        public static class EventHandlingComponent {

            @SuppressWarnings("unused")
            @EventHandler
            public void on(String event) {
                // a handler is required for a processor to be constructed at all
            }
        }
    }

    @Configuration
    static class GlobalSubscribingCustomizationContext {

        @Bean
        public AtomicInteger customizationInvocations() {
            return new AtomicInteger();
        }

        @Bean
        public SubscribingEventProcessorModule.Customization globalCustomization(AtomicInteger invocations) {
            return (configuration, processorConfiguration) -> {
                invocations.incrementAndGet();
                return processorConfiguration;
            };
        }
    }
}
