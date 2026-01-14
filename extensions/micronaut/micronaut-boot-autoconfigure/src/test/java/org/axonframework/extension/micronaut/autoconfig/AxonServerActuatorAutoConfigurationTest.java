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

import org.axonframework.extension.micronaut.actuator.axonserver.AxonServerHealthIndicator;
import org.axonframework.extension.micronaut.actuator.axonserver.AxonServerStatusAggregator;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.extension.micronaut.util.GrpcServerStub;
import org.axonframework.extension.micronaut.util.TcpUtils;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link AxonServerActuatorAutoConfiguration}.
 *
 * @author Steven van Beelen
 */
@Disabled("TODO #3496")
class AxonServerActuatorAutoConfigurationTest {

    private ApplicationContextRunner testApplicationContext;

    @BeforeEach
    void setUp() {
        testApplicationContext = new ApplicationContextRunner();
    }

    @BeforeAll
    static void beforeAll() {
        System.setProperty("axon.axonserver.servers", GrpcServerStub.DEFAULT_HOST + ":" + TcpUtils.findFreePort());
    }

    @AfterAll
    static void afterAll() {
        System.clearProperty("axon.axonserver.servers");
    }

    @Test
    void axonServerHealthIndicatorIsNotCreatedForAxonServerDisabled() {
        testApplicationContext.withUserConfiguration(TestContext.class)
                              .withPropertyValues("axon.axonserver.enabled:false")
                              .run(context -> {
                                  assertThat(context).doesNotHaveBean(AxonServerHealthIndicator.class);
                                  assertThat(context).doesNotHaveBean(AxonServerStatusAggregator.class);
                              });
    }

    @Test
    void axonServerHealthIndicatorIsCreated() {
        testApplicationContext.withUserConfiguration(TestContextWithMockAxonServer.class)
                              .withPropertyValues("axon.axonserver.enabled:true")
                              .run(context -> {
                                  assertThat(context).hasSingleBean(AxonServerHealthIndicator.class);
                                  assertThat(context).hasSingleBean(AxonServerStatusAggregator.class);
                              });
    }

    @Test
    void serviceIsIgnoredIfLibraryIsNotPresent() {
        testApplicationContext.withUserConfiguration(TestContextWithMockAxonServer.class)
                              .withClassLoader(new FilteredClassLoader(AxonServerConnectionManager.class))
                              .run(context -> {
                                  assertThat(context).doesNotHaveBean(AxonServerHealthIndicator.class);
                                  assertThat(context).doesNotHaveBean(AxonServerStatusAggregator.class);
                              });
    }

    @Test
    void axonServerHealthIndicatorCanBeExchangedForOwnBeans() {
        // Test autoconfig adds beans if we don't specify any
        testApplicationContext.withUserConfiguration(TestContextWithMockAxonServer.class)
                              .withPropertyValues("axon.axonserver.enabled:true")
                              .run(context -> {
                                  assertThat(context).hasSingleBean(SimpleStatusAggregator.class);
                                  assertThat(context).getBean(SimpleStatusAggregator.class)
                                                     .isNotOfAnyClassIn(CustomSimpleStatusAggregator.class);

                                  assertThat(context).hasSingleBean(AxonServerHealthIndicator.class);
                                  assertThat(context).getBeanNames(AxonServerHealthIndicator.class)
                                                     .isNotOfAnyClassIn(CustomAxonServerServerHealthIndicator.class);
                              });

        // Test Autoconfig does not add beans if we specify them
        testApplicationContext.withUserConfiguration(
                                      TestContextWithMockAxonServer.class, TestContextWithCustomBean.class
                              )
                              .withPropertyValues("axon.axonserver.enabled:true")
                              .run(context -> {
                                  //existence
                                  assertThat(context).hasSingleBean(CustomSimpleStatusAggregator.class);
                                  assertThat(context).hasSingleBean(CustomAxonServerServerHealthIndicator.class);

                                  // access over supertype
                                  assertThat(context).getBean(SimpleStatusAggregator.class)
                                                     .isOfAnyClassIn(CustomSimpleStatusAggregator.class);
                                  assertThat(context).getBean(AxonServerHealthIndicator.class)
                                                     .isOfAnyClassIn(CustomAxonServerServerHealthIndicator.class);
                              });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class TestContext {

    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class TestContextWithMockAxonServer {

        @Bean(initMethod = "start", destroyMethod = "shutdown")
        public GrpcServerStub grpcServerStub(@Value("${axon.axonserver.servers}") String servers) {
            return new GrpcServerStub(Integer.parseInt(servers.split(":")[1]));
        }
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class TestContextWithCustomBean {

        @Bean
        public CustomSimpleStatusAggregator customAxonServerStatusAggregator() {
            return new CustomSimpleStatusAggregator();
        }

        @Bean
        public CustomAxonServerServerHealthIndicator customAxonServerServerHealthIndicator(
                AxonServerConnectionManager connectionManager
        ) {
            return new CustomAxonServerServerHealthIndicator(connectionManager);
        }
    }
}

class CustomSimpleStatusAggregator extends SimpleStatusAggregator {

}

class CustomAxonServerServerHealthIndicator extends AxonServerHealthIndicator {

    public CustomAxonServerServerHealthIndicator(AxonServerConnectionManager connectionManager) {
        super(connectionManager);
    }
}