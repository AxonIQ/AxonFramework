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

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating that {@link AxonTimeoutAutoConfiguration}, as part of the full set of autoconfiguration classes
 * picked up through {@code spring.factories}/{@code AutoConfiguration.imports}, does not break Spring context
 * creation.
 * <p>
 * Earlier timeout wiring resolved the {@link CommandBus} and {@link QueryBus} beans while those beans were still being
 * constructed, which Spring reported as a {@code BeanCurrentlyInCreationException}. The current
 * {@code HandlerTimeoutConfigurationEnhancer} and {@code TimeoutUnitOfWorkFactoryConfigurationEnhancer} decorate named
 * {@code UnitOfWorkFactory} components instead of resolving the {@code CommandBus}/{@code QueryBus} beans directly, so
 * this regression no longer applies.
 *
 * @author Steven van Beelen
 */
class AxonTimeoutAutoConfigurationFullContextTest {

    @Test
    void fullAutoConfigurationSetBootsWithoutCircularBeanCreation() {
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(AppContext.class)
                        .web(WebApplicationType.NONE)
                        .run();

        assertThat(context.getBean(CommandBus.class)).isNotNull();
        assertThat(context.getBean(QueryBus.class)).isNotNull();

        context.close();
    }

    @Configuration
    @EnableAutoConfiguration
    static class AppContext {

    }
}
