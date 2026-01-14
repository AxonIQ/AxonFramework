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

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.extension.micronaut.config.MicronautAxonApplication;
import org.axonframework.extension.micronaut.config.MicronautComponentRegistry;
import org.axonframework.extension.micronaut.config.MicronautLifecycleRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration class that defines the {@link AxonConfiguration} and
 * {@link ComponentRegistry} implementations that supports beans to be accessed as
 * components.
 * <p>
 * Lifecycle handlers from the {@link LifecycleRegistry} are managed by the Spring
 * Application context to allow "weaving" of these lifecycles with the Spring lifecycles.
 * <p>
 * This autoconfiguration will construct the beans such that they allow for a hierarchical Spring Application Context.
 *
 * @author Allard Buijze
 * @author Josh Long
 * @since 3.0.0
 */
@AutoConfiguration
public class AxonAutoConfiguration {

    /**
     * Bean creation method registering a {@link MicronautComponentRegistry} <b>if</b> the current Application Context does
     * not have one already.
     * <p>
     * By only checking the {@link SearchStrategy#CURRENT current} context, we ensure that every context for a
     * hierarchical Spring Application Context receives a {@link ComponentRegistry}
     * instance.
     */
    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    MicronautComponentRegistry springComponentRegistry(ApplicationContext applicationContext,
                                                       MicronautLifecycleRegistry micronautLifecycleRegistry) {
        return new MicronautComponentRegistry(applicationContext, micronautLifecycleRegistry);
    }

    /**
     * Bean creation method registering a {@link MicronautLifecycleRegistry} when there is none <b>anywhere</b>.
     * <p>
     * By checking {@link SearchStrategy#ALL everywhere}, we ensure that there only is a single
     * {@link LifecycleRegistry} at all times. Even when a hierarchical Spring
     * Application Context is in place.
     */
    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.ALL)
    MicronautLifecycleRegistry springLifecycleRegistry() {
        return new MicronautLifecycleRegistry();
    }

    /**
     * Bean creation method registering a {@link MicronautAxonApplication} when there is none <b>anywhere</b>.
     * <p>
     * By checking {@link SearchStrategy#ALL everywhere}, we ensure that there only is a single main
     * {@link ApplicationConfigurer} at all times. Even when a hierarchical Spring
     * Application Context is in place.
     */
    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.ALL)
    MicronautAxonApplication axonApplication(MicronautComponentRegistry micronautComponentRegistry,
                                             MicronautLifecycleRegistry micronautLifecycleRegistry) {
        return new MicronautAxonApplication(micronautComponentRegistry, micronautLifecycleRegistry);
    }

    /**
     * Bean creation method registering a {@link AxonConfiguration} if the Application Context does not have one
     * already
     * <b>and</b> if the current Application Context has a {@link MicronautAxonApplication}.
     * <p>
     * By only checking the {@link SearchStrategy#CURRENT current} context for a {@code SpringAxonApplication}, we
     * ensure that there only is a single main {@link AxonConfiguration} at all times. Even when a hierarchical Spring
     * Application Context is in place.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(value = MicronautAxonApplication.class, search = SearchStrategy.CURRENT)
    AxonConfiguration axonApplicationConfiguration(MicronautAxonApplication axonApplication) {
        return axonApplication.build();
    }

    /**
     * Bean creation method registering a {@link Configuration} <b>if</b> the current Application Context does not have
     * one already.
     * <p>
     * This bean is <b>only</b> created when a hierarchical Spring Application Context is being used, since only then
     * will it <b>not</b> clash with the {@link #axonApplicationConfiguration(MicronautAxonApplication)}. This holds since
     * the returned {@link AxonConfiguration} is a {@code Configuration} instance.
     */
    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    @ConditionalOnBean(value = AxonConfiguration.class, search = SearchStrategy.ALL)
    Configuration axonConfiguration(MicronautComponentRegistry micronautComponentRegistry) {
        return micronautComponentRegistry.configuration();
    }
}
