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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.extension.spring.config.EventProcessorDefinition;
import org.axonframework.extension.spring.config.EventProcessorSettings;
import org.axonframework.extension.springboot.EventProcessorProperties;
import org.axonframework.extension.springboot.fixture.event.test1.FirstHandler;
import org.axonframework.extension.springboot.fixture.event.test2.Test2EventHandlingConfiguration;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.context.annotation.Import;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating configuration through a properties file, adjusting the {@link EventProcessorProperties}, in
 * combination with the {@link EventProcessorDefinition}.
 *
 * @author Allard Buijze
 * @author Simon Zambrovski
 */
class EventProcessorPropertiesAndDefinitionInteractionIT {

    private static final String KEY1 = "org.axonframework.extension.springboot.fixture.event.test1";
    private static final String KEY2 = "org.axonframework.extension.springboot.fixture.event.test2";

    @SuppressWarnings("unused")
    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    @ComponentScan(basePackageClasses = FirstHandler.class) // explicit scan to a location not in the same sub-package
    @Import(Test2EventHandlingConfiguration.class) // explicit configuration import
    private static class MyCustomContext {

        @Bean(name = "tokenStore")
        public TokenStore store1() {
            return new InMemoryTokenStore();
        }

        @Bean(name = "store2")
        public TokenStore store2() {
            return new InMemoryTokenStore();
        }
    }

    @org.springframework.context.annotation.Configuration
    public static class SubscribingEventProcessorDefinitionContext {

        @Bean
        public EventProcessorDefinition processor1Definition() {
            return EventProcessorDefinition.subscribing(KEY1)
                                      .assigningHandlers(p -> p.beanType().getPackageName().equals(KEY1))
                                      .customized(c -> c.withInterceptor(new StubInterceptor()));
        }
    }

    @org.springframework.context.annotation.Configuration
    public static class PooledStreamingEventProcessorDefinitionContext {

        @Bean
        public EventProcessorDefinition processor2Definition() {
            return EventProcessorDefinition.pooledStreaming(KEY2)
                                      .assigningHandlers(p -> p.beanType().getPackageName().equals(KEY2))
                                      .customized(c -> c.withInterceptor(new StubInterceptor()));
        }
    }

    @org.springframework.context.annotation.Configuration
    public static class BroadlyMatchingProcessorDefinitionContext {

        @Bean
        public EventProcessorDefinition processor3Definition() {
            return EventProcessorDefinition.pooledStreaming(KEY2)
                                      .assigningHandlers(p -> true)
                                      .notCustomized();
        }
    }

    private static class StubInterceptor implements MessageHandlerInterceptor<Message> {

        @Override
        public @NonNull MessageStream<?> interceptOnHandle(@NonNull Message message,
                                                           @NonNull ProcessingContext context,
                                                           @NonNull MessageHandlerInterceptorChain<Message> interceptorChain) {
            return interceptorChain.proceed(message, context);
        }
    }

    @Nested
    @SpringBootTest(
            classes = {EventProcessorPropertiesAndDefinitionInteractionIT.MyCustomContext.class},
            properties = {
                    "axon.eventstorage.jpa.polling-interval=0",
                    "axon.eventhandling.processors[org.axonframework.extension.springboot.fixture.event.test1].mode=pooled",
                    "axon.eventhandling.processors[org.axonframework.extension.springboot.fixture.event.test1].initialSegmentCount=73",
                    "axon.eventhandling.processors[org.axonframework.extension.springboot.fixture.event.test1].tokenClaimInterval=7",
                    "axon.eventhandling.processors[org.axonframework.extension.springboot.fixture.event.test1].tokenClaimIntervalTimeUnit=SECONDS",
                    "axon.eventhandling.processors[org.axonframework.extension.springboot.fixture.event.test1].threadCount=5",
                    "axon.eventhandling.processors[org.axonframework.extension.springboot.fixture.event.test1].batchSize=3",
                    "axon.eventhandling.processors[org.axonframework.extension.springboot.fixture.event.test1].tokenStore=store2",
            },
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    class TwoPoolProcessorsTest {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private EventProcessorSettings.MapWrapper properties;

        @Test
        void processorConfigurationWithCustomValues() {
            assertThat(properties.settings().get(KEY1)).isNotNull();

            AxonConfiguration axonApplication = context.getBean(AxonConfiguration.class);

            Configuration eventProcessorConfig1 = axonApplication.getModuleConfiguration(
                    "EventProcessor[" + KEY1 + "]").orElseThrow();
            assertThat(eventProcessorConfig1.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(eventProcessorConfig1.getOptionalComponent(StreamingEventProcessor.class, KEY1)).isPresent();

            Configuration eventProcessorConfig2 = axonApplication.getModuleConfiguration(
                    "EventProcessor[" + KEY2 + "]").orElseThrow();
            assertThat(eventProcessorConfig2.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(eventProcessorConfig2.getOptionalComponent(StreamingEventProcessor.class, KEY2)).isPresent();
        }
    }

    @Nested
    class FailToLoadProcessorTest {

        static Stream<Arguments> configToError() {
            return Stream.of(
                    Arguments.of(
                            Map.of(
                                    "axon.eventhandling.processors[" + KEY1 + "].mode", "SUBSCRIBING",
                                    "axon.eventhandling.processors[" + KEY1 + "].source", "nonExisting1"
                            ),
                            "Could not find a mandatory Source with name 'nonExisting1' "
                                    + "for event processor '" + KEY1 + "'.",
                            57891
                    ),
                    Arguments.of(
                            Map.of(
                                    "axon.eventhandling.processors[" + KEY1 + "].source", "nonExisting1"
                            ),
                            "Could not find a mandatory Source with name 'nonExisting1' "
                                    + "for event processor '" + KEY1 + "'.",
                            57892
                    ),
                    Arguments.of(
                            Map.of(
                                    "axon.eventhandling.processors[" + KEY1 + "].tokenStore", "nonExisting1"
                            ),
                            "Could not find a mandatory TokenStore with name 'nonExisting1' "
                                    + "for event processor '" + KEY1 + "'.",
                            57893
                    )
            );
        }

        @ParameterizedTest
        @MethodSource("configToError")
        void dontStartWithWrongConfiguredProcessor(Map<String, String> parameters,
                                                   String message,
                                                   int port) {
            var app = new SpringApplication(MyCustomContext.class);
            app.setLogStartupInfo(false);
            Map<String, Object> props = new HashMap<>();
            props.put("server.port", port);
            props.put("logging.level.root", "OFF");
            props.put("logging.level.org.springframework.context.support.DefaultLifecycleProcessor", "OFF");
            props.put("spring.main.banner-mode", "off");
            props.put("axon.eventstorage.jpa.polling-interval", "0");
            props.putAll(parameters);
            app.setDefaultProperties(props);
            var e = assertThrows(
                    ApplicationContextException.class,
                    () -> {
                        var originalErr = System.err;
                        try {
                            System.setErr(new PrintStream(OutputStream.nullOutputStream()));
                            app.run();
                        } finally {
                            System.setErr(originalErr);
                        }
                    }
            );
            assertThat(e).isNotNull();
            assertThat(e.getCause()).isInstanceOf(AxonConfigurationException.class);
            assertThat(e.getCause().getMessage()).isEqualTo(message);
        }
    }

    @Nested
    @SpringBootTest(
            classes = {EventProcessorPropertiesAndDefinitionInteractionIT.MyCustomContext.class},
            properties = {
                    "axon.eventstorage.jpa.polling-interval=0",
                    "axon.eventhandling.processors[org.axonframework.extension.springboot.fixture.event.test1].mode=subscribing",
            },
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    class PooledAndSubscribingProcessorTest {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private EventProcessorSettings.MapWrapper properties;

        @Test
        void processorConfigurationSubscribed() {
            var settings = properties.settings().get(KEY1);
            assertThat(settings).isNotNull();
            assertThat(settings.processorMode()).isEqualTo(EventProcessorSettings.ProcessorMode.SUBSCRIBING);

            assertThat(properties.settings().get(KEY1)).isNotNull();

            AxonConfiguration axonApplication = context.getBean(AxonConfiguration.class);

            Configuration eventProcessorConfig1 = axonApplication.getModuleConfiguration(
                    "EventProcessor[" + KEY1 + "]").orElseThrow();
            assertThat(eventProcessorConfig1.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(eventProcessorConfig1.getOptionalComponent(EventProcessor.class, KEY1)).isPresent();

            Configuration eventProcessorConfig2 = axonApplication.getModuleConfiguration(
                    "EventProcessor[" + KEY2 + "]").orElseThrow();
            assertThat(eventProcessorConfig2.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(eventProcessorConfig2.getOptionalComponent(StreamingEventProcessor.class, KEY2)).isPresent();
        }
    }

    @Nested
    @SpringBootTest(
            classes = {EventProcessorPropertiesAndDefinitionInteractionIT.MyCustomContext.class,
                    SubscribingEventProcessorDefinitionContext.class},
            properties = {
                    "axon.eventstorage.jpa.polling-interval=0"
            },
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    class PooledAndSubscribingProcessorUsingSingleBeanConfigurationTest {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private EventProcessorSettings.MapWrapper properties;

        @Test
        void processorConfigurationSubscribed() {
            // we expect no explicit settings for this processor
            assertThat(properties.settings().get(KEY1)).isNull();

            AxonConfiguration axonApplication = context.getBean(AxonConfiguration.class);

            Configuration eventProcessorConfig1 = axonApplication.getModuleConfiguration(
                    "EventProcessor[" + KEY1 + "]").orElseThrow();
            assertThat(eventProcessorConfig1.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(eventProcessorConfig1.getOptionalComponent(EventProcessor.class, KEY1)).isPresent();
            assertThat(eventProcessorConfig1.getOptionalComponent(SubscribingEventProcessorConfiguration.class)).hasValueSatisfying(
                    // there is an additional custom interceptor
                    config -> assertThatCollection(config.interceptors()).anyMatch(StubInterceptor.class::isInstance)
            );

            Configuration eventProcessorConfig2 = axonApplication.getModuleConfiguration(
                    "EventProcessor[" + KEY2 + "]").orElseThrow();
            assertThat(eventProcessorConfig2.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(eventProcessorConfig2.getOptionalComponent(StreamingEventProcessor.class, KEY2)).isPresent();
            assertThat(eventProcessorConfig2.getOptionalComponent(PooledStreamingEventProcessorConfiguration.class)).hasValueSatisfying(
                    // we should not see the custom interceptor here
                    config -> assertThatCollection(config.interceptors()).noneMatch(StubInterceptor.class::isInstance)
            );
        }
    }

    @Nested
    @SpringBootTest(
            classes = {EventProcessorPropertiesAndDefinitionInteractionIT.MyCustomContext.class,
                    SubscribingEventProcessorDefinitionContext.class,
                    PooledStreamingEventProcessorDefinitionContext.class},
            properties = {
                    "axon.eventstorage.jpa.polling-interval=0"
            },
            webEnvironment = SpringBootTest.WebEnvironment.NONE
    )
    class PooledAndSubscribingProcessorUsingBothBeanConfigurationTest {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private EventProcessorSettings.MapWrapper properties;

        @Test
        void processorConfigurationSubscribed() {
            // we expect no explicit settings for this processor
            assertThat(properties.settings().get(KEY1)).isNull();

            AxonConfiguration axonApplication = context.getBean(AxonConfiguration.class);

            Configuration eventProcessorConfig1 = axonApplication.getModuleConfiguration(
                    "EventProcessor[" + KEY1 + "]").orElseThrow();
            assertThat(eventProcessorConfig1.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(eventProcessorConfig1.getOptionalComponent(EventProcessor.class, KEY1)).isPresent();
            assertThat(eventProcessorConfig1.getOptionalComponent(SubscribingEventProcessorConfiguration.class)).hasValueSatisfying(
                    // there is an additional custom interceptor
                    config -> assertThatCollection(config.interceptors()).anyMatch(StubInterceptor.class::isInstance)
            );

            Configuration eventProcessorConfig2 = axonApplication.getModuleConfiguration(
                    "EventProcessor[" + KEY2 + "]").orElseThrow();
            assertThat(eventProcessorConfig2.getComponents(EventProcessor.class)).isNotEmpty();
            assertThat(eventProcessorConfig2.getOptionalComponent(StreamingEventProcessor.class, KEY2)).isPresent();
            assertThat(eventProcessorConfig2.getOptionalComponent(PooledStreamingEventProcessorConfiguration.class)).hasValueSatisfying(
                    // we should not see the custom interceptor here
                    config -> assertThatCollection(config.interceptors()).anyMatch(StubInterceptor.class::isInstance)
            );
        }
    }

    @Nested
    class OverlappingBeanAssignmentConfigurationTest {

        @Test
        void processorConfigurationSubscribed() {
            var app = new SpringApplication(MyCustomContext.class,
                                            SubscribingEventProcessorDefinitionContext.class,
                                            BroadlyMatchingProcessorDefinitionContext.class);
            app.setDefaultProperties(Map.of("axon.eventstorage.jpa.polling-interval", "0"));
            app.setLogStartupInfo(false);

            app.setWebApplicationType(WebApplicationType.NONE);
            assertThatException().isThrownBy(app::run)
                                 .havingRootCause()
                                 .withMessageContaining("multiple processors selectors");
        }
    }
}
