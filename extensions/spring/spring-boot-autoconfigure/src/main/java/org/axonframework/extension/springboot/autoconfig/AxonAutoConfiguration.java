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

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.extension.spring.config.SpringAxonApplication;
import org.axonframework.extension.spring.config.SpringComponentRegistry;
import org.axonframework.extension.spring.config.SpringLifecycleRegistry;
import org.axonframework.extension.spring.config.annotation.HandlerDefinitionFactoryBean;
import org.axonframework.extension.spring.config.annotation.SpringParameterResolverFactoryBean;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SearchStrategy;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Autoconfiguration class that defines the {@link AxonConfiguration} and {@link ComponentRegistry} implementations that
 * supports beans to be accessed as components.
 * <p>
 * Lifecycle handlers from the {@link LifecycleRegistry} are managed by the Spring Application context to allow
 * "weaving" of these lifecycles with the Spring lifecycles.
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
     * Bean creation method registering a {@link SpringComponentRegistry} <b>if</b> the current Application Context does
     * not have one already.
     * <p>
     * By only checking the {@link SearchStrategy#CURRENT current} context, we ensure that every context for a
     * hierarchical Spring Application Context receives a {@link ComponentRegistry} instance.
     */
    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    SpringComponentRegistry springComponentRegistry(ApplicationContext applicationContext,
                                                    SpringLifecycleRegistry springLifecycleRegistry) {
        return new SpringComponentRegistry(applicationContext, springLifecycleRegistry);
    }

    /**
     * Bean creation method registering a {@link SpringLifecycleRegistry} when there is none <b>anywhere</b>.
     * <p>
     * By checking {@link SearchStrategy#ALL everywhere}, we ensure that there only is a single
     * {@link LifecycleRegistry} at all times. Even when a hierarchical Spring Application Context is in place.
     */
    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.ALL)
    SpringLifecycleRegistry springLifecycleRegistry() {
        return new SpringLifecycleRegistry();
    }

    /**
     * Bean creation method registering a {@link SpringAxonApplication} when there is none <b>anywhere</b>.
     * <p>
     * By checking {@link SearchStrategy#ALL everywhere}, we ensure that there only is a single main
     * {@link ApplicationConfigurer} at all times. Even when a hierarchical Spring Application Context is in place.
     */
    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.ALL)
    SpringAxonApplication axonApplication(SpringComponentRegistry springComponentRegistry,
                                          SpringLifecycleRegistry springLifecycleRegistry) {
        return new SpringAxonApplication(springComponentRegistry, springLifecycleRegistry);
    }

    /**
     * Bean creation method registering a {@link AxonConfiguration} if the Application Context does not have one
     * already
     * <b>and</b> if the current Application Context has a {@link SpringAxonApplication}.
     * <p>
     * By only checking the {@link SearchStrategy#CURRENT current} context for a {@code SpringAxonApplication}, we
     * ensure that there only is a single main {@link AxonConfiguration} at all times. Even when a hierarchical Spring
     * Application Context is in place.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(value = SpringAxonApplication.class, search = SearchStrategy.CURRENT)
    AxonConfiguration axonApplicationConfiguration(SpringAxonApplication axonApplication) {
        return axonApplication.build();
    }

    /**
     * Bean creation method registering a {@link Configuration} <b>if</b> the current Application Context does not have
     * one already.
     * <p>
     * This bean is <b>only</b> created when a hierarchical Spring Application Context is being used, since only then
     * will it <b>not</b> clash with the {@link #axonApplicationConfiguration(SpringAxonApplication)}. This holds since
     * the returned {@link AxonConfiguration} is a {@code Configuration} instance.
     */
    @Bean
    @ConditionalOnMissingBean(search = SearchStrategy.CURRENT)
    @ConditionalOnBean(value = AxonConfiguration.class, search = SearchStrategy.ALL)
    Configuration axonConfiguration(SpringComponentRegistry springComponentRegistry) {
        return springComponentRegistry.configuration();
    }

    /**
     * Bean creation method registering the {@link SpringParameterResolverFactoryBean}.
     * <p>
     * This {@link ParameterResolverFactory} {@link org.springframework.beans.factory.FactoryBean} will make it so all
     * annotated handling components will use the
     * {@link org.axonframework.extension.spring.config.annotation.SpringBeanDependencyResolverFactory},
     * {@link org.axonframework.extension.spring.config.annotation.SpringBeanParameterResolverFactory}, and the given
     * {@code parameterResolverFactories} to derive parameters to inject in
     * {@link org.axonframework.messaging.core.annotation.MessageHandler} annotated methods.
     *
     * @param parameterResolverFactories the list of {@code ParameterResolverFactories} to pass along to the
     *                                   {@link SpringParameterResolverFactoryBean}
     * @return a {@link ParameterResolverFactory} {@link org.springframework.beans.factory.FactoryBean}
     */
    @Primary
    @Bean
    SpringParameterResolverFactoryBean parameterResolverFactory(
            List<ParameterResolverFactory> parameterResolverFactories
    ) {
        SpringParameterResolverFactoryBean springParameterResolverFactoryBean =
                new SpringParameterResolverFactoryBean();
        springParameterResolverFactoryBean.setAdditionalFactories(parameterResolverFactories);
        return springParameterResolverFactoryBean;
    }

    /**
     * Bean creation method registering the {@link HandlerDefinitionFactoryBean}.
     * <p>
     * This {@link HandlerDefinition} {@link org.springframework.beans.factory.FactoryBean} will make it so all
     * annotated handling components will use the given {@code handlerDefinitions}, {@code handlerEnhancerDefinitions},
     * and Axon's default enhancer definitions to derive the required enhancements for
     * {@link org.axonframework.messaging.core.annotation.MessageHandler} annotated methods.
     *
     * @param handlerDefinitions         the list of {@code HandlerDefinitions} to pass along to the
     *                                   {@link HandlerDefinitionFactoryBean}
     * @param handlerEnhancerDefinitions the list of {@code HandlerEnhancerDefinitions} to pass along to the
     *                                   {@link HandlerDefinitionFactoryBean}
     * @return a {@link HandlerDefinition} {@link org.springframework.beans.factory.FactoryBean}
     */
    @Primary
    @Bean
    HandlerDefinitionFactoryBean handlerDefinition(List<HandlerDefinition> handlerDefinitions,
                                                   List<HandlerEnhancerDefinition> handlerEnhancerDefinitions) {
        return new HandlerDefinitionFactoryBean(handlerDefinitions, handlerEnhancerDefinitions);
    }
}
