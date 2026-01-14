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

package org.axonframework.extension.micronaut;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.Module;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorModule;
import org.axonframework.extension.micronaut.config.MicronautComponentRegistry;
import org.axonframework.extension.micronaut.fixture.event.test1.FirstHandler;
import org.axonframework.extension.micronaut.fixture.event.test2.Test2EventHandlingConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating configuration through a properties file, adjusting the {@link EventProcessorProperties}.
 *
 * @author Allard Buijze
 * @author Simon Zambrovski
 */
class EventProcessorConfigurationTest {

    private static final String KEY1 = "org.axonframework.extension.springboot.fixture.event.test1";
    private static final String KEY2 = "org.axonframework.extension.springboot.fixture.event.test2";

    @Nested
    @SpringBootTest(
            classes = {EventProcessorConfigurationTest.MyCustomContext.class},
            properties = {
                    "axon.axonserver.enabled=false",
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
        private EventProcessorProperties properties;

        @Test
        void processorConfigurationWithCustomValues() throws Exception {


            assertThat(properties.getProcessors().get(KEY1)).isNotNull();

            assertThat(context.getBean(MicronautComponentRegistry.class)).isNotNull();
            Map<String, Module> modules = ReflectionUtils.getFieldValue(
                    MicronautComponentRegistry.class.getDeclaredField("modules"),
                    context.getBean(MicronautComponentRegistry.class)
            );
            assertThat(modules).isNotNull();
            assertThat(modules).hasSize(4);
            var module1 = modules.get("EventProcessor[" + KEY1 + "]");
            assertThat(module1).isNotNull();
            assertThat(module1).isInstanceOf(PooledStreamingEventProcessorModule.class);

            var module2 = modules.get("EventProcessor[" + KEY2 + "]");
            assertThat(module2).isNotNull();
            assertThat(module2).isInstanceOf(PooledStreamingEventProcessorModule.class);
        }
    }

    @Nested
    @SpringBootTest(
            classes = {EventProcessorConfigurationTest.MyCustomContext.class},
            properties = {
                    "axon.axonserver.enabled=false",
                    "axon.eventstorage.jpa.polling-interval=0",
                    "axon.eventhandling.processors[org.axonframework.extension.springboot.fixture.event.test1].mode=subscribing",
            },
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    class PooledAndSubscribingProcessorTest {

        @Autowired
        private ApplicationContext context;

        @Autowired
        private EventProcessorProperties properties;

        @Test
        void processorConfigurationSubscribed() throws Exception {

            var settings = properties.getProcessors().get(KEY1);
            assertThat(settings).isNotNull();
            assertThat(settings.getMode()).isEqualTo(EventProcessorProperties.Mode.SUBSCRIBING);

            assertThat(context.getBean(MicronautComponentRegistry.class)).isNotNull();
            Map<String, Module> modules = ReflectionUtils.getFieldValue(
                    MicronautComponentRegistry.class.getDeclaredField("modules"),
                    context.getBean(MicronautComponentRegistry.class)
            );
            assertThat(modules).isNotNull();
            assertThat(modules).hasSize(4);

            Map<String, Configuration> modulesConfigurations = ReflectionUtils.getFieldValue(
                    MicronautComponentRegistry.class.getDeclaredField("moduleConfigurations"),
                    context.getBean(MicronautComponentRegistry.class)
            );

            var ep1 = "EventProcessor[" + KEY1 + "]";
            var ep2 = "EventProcessor[" + KEY2 + "]";

            assertThat(modulesConfigurations).hasSize(2);
            assertThat(modulesConfigurations).containsKeys(ep1, ep2);

            var module1 = modules.get(ep1);
            assertThat(module1).isNotNull();
            assertThat(module1).isInstanceOf(SubscribingEventProcessorModule.class);

            var module2 = modules.get(ep2);
            assertThat(module2).isNotNull();
            assertThat(module2).isInstanceOf(PooledStreamingEventProcessorModule.class);
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
        @Disabled("Stopped working with port-already-in-use")
        void dontStartWithWrongConfiguredProcessor(Map<String, String> parameters, String message, int port) throws Exception {
            var app = new SpringApplication(MyCustomContext.class);
            app.setLogStartupInfo(false);
            Map<String, Object> props = new HashMap<>();
            props.put("server.port", port);
            props.put("logging.level.root", "OFF");
            props.put("logging.level.org.springframework.context.support.DefaultLifecycleProcessor", "OFF");
            props.put("axon.axonserver.enabled", "false");
            props.put("spring.main.banner-mode", "off");
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
}
