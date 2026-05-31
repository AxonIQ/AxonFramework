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

import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathHandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition;

import java.util.List;
import jakarta.annotation.Nonnull;

/**
 * Spring {@link FactoryBean} that creates a {@link HandlerDefinition} using configured {@link HandlerDefinition} and
 * {@link HandlerEnhancerDefinition) beans (e.g. those configured in a Spring Application Context) and complements those
 * found using a service loader on the Bean Class Loader.
 * <p>
 * This bean is to be used from a configuration file that auto wires the other {@link HandlerDefinition} and {@link
 * HandlerEnhancerDefinition} beans from the Application Context.
 *
 * @author Allard Buijze
 * @since 4.6.0
 */
//public class HandlerDefinitionFactoryBean implements FactoryBean<HandlerDefinition>, BeanClassLoaderAware {
//
//    private final List<HandlerDefinition> definitions;
//    private final List<HandlerEnhancerDefinition> enhancerDefinitions;
//    private ClassLoader beanClassLoader;
//
//    public HandlerDefinitionFactoryBean(List<HandlerDefinition> definitions,
//                                        List<HandlerEnhancerDefinition> enhancerDefinitions) {
//        this.definitions = definitions;
//        this.enhancerDefinitions = enhancerDefinitions;
//    }
//
//    @Override
//    public HandlerDefinition getObject() {
//        return MultiHandlerDefinition.ordered(resolveEnhancers(), resolveDefinitions());
//    }
//
//    private MultiHandlerDefinition resolveDefinitions() {
//        return MultiHandlerDefinition.ordered(ClasspathHandlerDefinition.forClassLoader(beanClassLoader),
//                                              MultiHandlerDefinition.ordered(definitions));
//    }
//
//    private MultiHandlerEnhancerDefinition resolveEnhancers() {
//        return MultiHandlerEnhancerDefinition.ordered(ClasspathHandlerEnhancerDefinition.forClassLoader(beanClassLoader),
//                                                      MultiHandlerEnhancerDefinition.ordered(enhancerDefinitions));
//    }
//
//    @Override
//    public Class<?> getObjectType() {
//        return HandlerDefinition.class;
//    }
//
//    @Override
//    public void setBeanClassLoader(@Nonnull ClassLoader classLoader) {
//        this.beanClassLoader = classLoader;
//    }
//}
