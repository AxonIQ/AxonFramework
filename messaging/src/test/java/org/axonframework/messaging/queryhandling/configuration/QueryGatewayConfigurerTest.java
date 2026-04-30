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
package org.axonframework.messaging.queryhandling.configuration;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.queryhandling.QueryShutdownManager;
import org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.queryhandling.gateway.ShutdownTrackingQueryGateway;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating {@link QueryGatewayConfigurer}.
 */
class QueryGatewayConfigurerTest {

    @Nested
    class WhenNoShutdownSpecIsConfigured {

        @Test
        void buildDefinition_producesDefinitionWithCorrectName() {
            // given
            QueryGatewayConfigurer configurer = new QueryGatewayConfigurer("reporting");

            // when
            ComponentDefinition<QueryGateway> definition = configurer.buildDefinition();

            // then
            assertThat(definition.name()).isEqualTo("reporting");
            assertThat(definition.rawType()).isEqualTo(QueryGateway.class);
        }

        @Test
        void buildDefinition_producesDefaultQueryGateway() {
            // given
            QueryGatewayConfigurer configurer = new QueryGatewayConfigurer("reporting");
            ComponentDefinition<QueryGateway> definition = configurer.buildDefinition();

            // when: resolve the component from a full configuration
            AxonConfiguration config = MessagingConfigurer.create()
                                                          .componentRegistry(cr -> cr.registerComponent(definition))
                                                          .build();
            QueryGateway gateway = config.getComponent(QueryGateway.class, "reporting");

            // then: plain DefaultQueryGateway, not wrapped
            assertThat(gateway).isInstanceOf(DefaultQueryGateway.class);
            assertThat(gateway).isNotInstanceOf(ShutdownTrackingQueryGateway.class);
        }
    }

    @Nested
    class WhenSubscriptionShutdownSpecIsConfigured {

        @Test
        void buildDefinition_producesShutdownTrackingGateway() {
            // given
            QueryGatewayConfigurer configurer = new QueryGatewayConfigurer("reporting")
                    .cancellingSubscriptionQueryOnShutdown(QueryShutdownManager.Spec::closeImmediately);
            ComponentDefinition<QueryGateway> definition = configurer.buildDefinition();

            // when
            AxonConfiguration config = MessagingConfigurer.create()
                                                          .componentRegistry(cr -> cr.registerComponent(definition))
                                                          .build();
            QueryGateway gateway = config.getComponent(QueryGateway.class, "reporting");

            // then: wrapped because a subscription shutdown spec was set
            assertThat(gateway).isInstanceOf(ShutdownTrackingQueryGateway.class);
        }
    }

    @Nested
    class WhenStreamingShutdownSpecIsConfigured {

        @Test
        void buildDefinition_producesShutdownTrackingGateway() {
            // given
            QueryGatewayConfigurer configurer = new QueryGatewayConfigurer("reporting")
                    .cancellingStreamingQueryOnShutdown(QueryShutdownManager.Spec::closeImmediately);
            ComponentDefinition<QueryGateway> definition = configurer.buildDefinition();

            // when
            AxonConfiguration config = MessagingConfigurer.create()
                                                          .componentRegistry(cr -> cr.registerComponent(definition))
                                                          .build();
            QueryGateway gateway = config.getComponent(QueryGateway.class, "reporting");

            // then: wrapped because a streaming shutdown spec was set
            assertThat(gateway).isInstanceOf(ShutdownTrackingQueryGateway.class);
        }
    }

    @Nested
    class WhenBothShutdownSpecsAreConfigured {

        @Test
        void buildDefinition_producesShutdownTrackingGateway() {
            // given
            QueryGatewayConfigurer configurer = new QueryGatewayConfigurer("reporting")
                    .cancellingSubscriptionQueryOnShutdown(QueryShutdownManager.Spec::closeImmediately)
                    .cancellingStreamingQueryOnShutdown(QueryShutdownManager.Spec::closeImmediately);
            ComponentDefinition<QueryGateway> definition = configurer.buildDefinition();

            // when
            AxonConfiguration config = MessagingConfigurer.create()
                                                          .componentRegistry(cr -> cr.registerComponent(definition))
                                                          .build();
            QueryGateway gateway = config.getComponent(QueryGateway.class, "reporting");

            // then: wrapped because both shutdown specs were set
            assertThat(gateway).isInstanceOf(ShutdownTrackingQueryGateway.class);
        }
    }
}
