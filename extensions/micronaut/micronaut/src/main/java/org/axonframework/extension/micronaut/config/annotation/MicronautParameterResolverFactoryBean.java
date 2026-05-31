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

package org.axonframework.extension.micronaut.config.annotation;

import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MultiParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import java.util.ArrayList;
import java.util.List;
import jakarta.annotation.Nonnull;

/**
 * Spring factory bean that creates a ParameterResolverFactory instance that is capable of resolving parameter values as
 * Spring Beans, in addition to the default behavior defined by Axon.
 *
 * @author Allard Buijze
 * @see MicronautBeanParameterResolverFactory
 * @see MicronautBeanDependencyResolverFactory
 * @see ClasspathParameterResolverFactory
 * @since 2.3.1
 */
//public class MicronautParameterResolverFactoryBean implements FactoryBean<ParameterResolverFactory>,
//        BeanClassLoaderAware, InitializingBean, ApplicationContextAware {
//
//    private final List<ParameterResolverFactory> factories = new ArrayList<>();
//    private ClassLoader classLoader;
//    private ApplicationContext applicationContext;
//
//    @Override
//    public ParameterResolverFactory getObject() {
//        return MultiParameterResolverFactory.ordered(factories);
//    }
//
//    @Override
//    public Class<?> getObjectType() {
//        return ParameterResolverFactory.class;
//    }
//
//    @Override
//    public boolean isSingleton() {
//        return true;
//    }
//
//    @Override
//    public void afterPropertiesSet() {
//        factories.add(ClasspathParameterResolverFactory.forClassLoader(classLoader));
//        factories.add(new MicronautBeanDependencyResolverFactory(applicationContext));
//        factories.add(new MicronautBeanParameterResolverFactory(applicationContext));
//    }
//
//    /**
//     * Defines any additional parameter resolver factories that need to be used to resolve parameters. By default, the
//     * ParameterResolverFactories found on the classpath, as well as a SpringBeanParameterResolverFactory are
//     * registered.
//     *
//     * @param additionalFactories The extra factories to register
//     * @see MicronautBeanParameterResolverFactory
//     * @see MicronautBeanDependencyResolverFactory
//     * @see ClasspathParameterResolverFactory
//     */
//    public void setAdditionalFactories(List<ParameterResolverFactory> additionalFactories) {
//        this.factories.addAll(additionalFactories);
//    }
//
//    @Override
//    public void setBeanClassLoader(@Nonnull ClassLoader classLoader) {
//        this.classLoader = classLoader;
//    }
//
//    @Override
//    public void setApplicationContext(@Nonnull ApplicationContext applicationContext) throws BeansException {
//        this.applicationContext = applicationContext;
//    }
//}
