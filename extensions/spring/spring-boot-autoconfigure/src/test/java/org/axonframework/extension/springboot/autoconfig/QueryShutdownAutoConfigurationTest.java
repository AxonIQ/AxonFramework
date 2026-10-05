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

import org.axonframework.messaging.queryhandling.QueryShutdownManager;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.queryhandling.gateway.ShutdownTrackingQueryGateway;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link QueryShutdownAutoConfiguration}.
 *
 * @author Allard Buijze
 */
class QueryShutdownAutoConfigurationTest {

    private ApplicationContextRunner testContext;

    @BeforeEach
    void setUp() {
        testContext = new ApplicationContextRunner()
                .withPropertyValues("axon.eventstorage.jpa.polling-interval=0")
                .withUserConfiguration(DefaultContext.class);
    }

    @Nested
    class WhenNoShutdownManagerBeanIsDefined {

        @Test
        void noLifecycleBeanIsCreated() {
            testContext.run(context -> {
                // given / when: no QueryShutdownManager beans defined

                // then: no shutdown lifecycle handler registered
                assertThat(context).doesNotHaveBean("queryShutdownManagerLifecycle");
            });
        }

        @Test
        void queryGatewayIsNotWrapped() {
            testContext.run(context -> {
                // given / when: no QueryShutdownManager beans defined

                // then: QueryGateway remains unwrapped
                assertThat(context.getBean(QueryGateway.class))
                        .isNotInstanceOf(ShutdownTrackingQueryGateway.class);
            });
        }
    }

    @Nested
    class WhenASingleShutdownManagerIsDefined {

        @Test
        void lifecycleBeanIsRegisteredAtWebServerGracefulShutdownPhase() {
            testContext.withUserConfiguration(SingleManagerContext.class).run(context -> {
                // given / when: exactly one QueryShutdownManager bean

                // then: lifecycle handler is registered at the same phase as web server graceful shutdown
                assertThat(context).hasBean("queryShutdownManagerLifecycle");
                SmartLifecycle lifecycle = context.getBean("queryShutdownManagerLifecycle", SmartLifecycle.class);
                assertThat(lifecycle.getPhase())
                        .isEqualTo(SmartLifecycle.DEFAULT_PHASE - 1024);
            });
        }

        @Test
        void queryGatewayIsNotWrapped() {
            testContext.withUserConfiguration(SingleManagerContext.class).run(context -> {
                // given / when: exactly one QueryShutdownManager bean
                // gateway wrapping is not automatic; users must configure it explicitly via queryGateway(String, Consumer)

                // then: QueryGateway is not wrapped automatically
                assertThat(context.getBean(QueryGateway.class))
                        .isNotInstanceOf(ShutdownTrackingQueryGateway.class);
            });
        }
    }

    @Nested
    class WhenMultipleShutdownManagersAreDefined {

        @Test
        void lifecycleBeanIsRegisteredForAllManagers() {
            testContext.withUserConfiguration(MultipleManagerContext.class).run(context -> {
                // given / when: two QueryShutdownManager beans defined

                // then: lifecycle handler is registered covering both managers
                assertThat(context).hasBean("queryShutdownManagerLifecycle");
                assertThat(context.getBeansOfType(QueryShutdownManager.class)).hasSize(2);
            });
        }

        @Test
        void queryGatewayIsNotWrapped() {
            testContext.withUserConfiguration(MultipleManagerContext.class).run(context -> {
                // given / when: multiple managers with no primary - policy is ambiguous

                // then: gateway is not wrapped; user must call track() at individual call sites
                assertThat(context.getBean(QueryGateway.class))
                        .isNotInstanceOf(ShutdownTrackingQueryGateway.class);
            });
        }
    }

    @Nested
    class WhenMultipleManagersButOneIsPrimary {

        @Test
        void queryGatewayIsNotWrappedEvenWithPrimary() {
            testContext.withUserConfiguration(MultipleManagerWithPrimaryContext.class).run(context -> {
                // given / when: multiple managers, one marked @Primary
                // the @Primary concept is Spring-specific; Axon sees two managers and does not wrap

                // then: gateway is not wrapped; user must call track() at individual call sites
                assertThat(context.getBean(QueryGateway.class))
                        .isNotInstanceOf(ShutdownTrackingQueryGateway.class);
            });
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class DefaultContext {
    }

    @Configuration
    static class SingleManagerContext {

        @Bean
        public QueryShutdownManager queryShutdownManager() {
            return QueryShutdownManager.closeImmediately();
        }
    }

    @Configuration
    static class MultipleManagerContext {

        @Bean
        public QueryShutdownManager sseShutdownManager() {
            return QueryShutdownManager.withGracePeriod(Duration.ofSeconds(10));
        }

        @Bean
        public QueryShutdownManager internalShutdownManager() {
            return QueryShutdownManager.closeImmediately();
        }
    }

    @Configuration
    static class MultipleManagerWithPrimaryContext {

        @Bean
        @Primary
        public QueryShutdownManager primaryShutdownManager() {
            return QueryShutdownManager.closeImmediately();
        }

        @Bean
        public QueryShutdownManager secondaryShutdownManager() {
            return QueryShutdownManager.withGracePeriod(Duration.ofSeconds(5));
        }
    }
}
