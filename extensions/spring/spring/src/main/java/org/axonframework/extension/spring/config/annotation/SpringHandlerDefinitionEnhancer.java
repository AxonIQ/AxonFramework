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

package org.axonframework.extension.spring.config.annotation;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition;
import org.axonframework.messaging.core.configuration.reflection.HandlerDefinitionUtils;
import org.axonframework.messaging.core.configuration.reflection.HandlerEnhancerDefinitionUtils;

import java.util.List;

/**
 * {@link ConfigurationEnhancer} that composes {@link HandlerDefinition} and {@link HandlerEnhancerDefinition} beans
 * from a Spring {@link org.springframework.context.ApplicationContext} into the {@link ComponentRegistry}.
 * <p>
 * This enhancer is picked up automatically by
 * {@link org.axonframework.extension.spring.config.SpringComponentRegistry}, alongside classpath/
 * {@link java.util.ServiceLoader}-discovered {@code ConfigurationEnhancers} such as the one seeding the default,
 * classpath-discovered {@code HandlerDefinition} and {@code HandlerEnhancerDefinition}. Both compose into the same
 * {@code ComponentRegistry} component through
 * {@link HandlerDefinitionUtils#registerToComponentRegistry(ComponentRegistry, java.util.function.Function)} and
 * {@link HandlerEnhancerDefinitionUtils#registerToComponentRegistry(ComponentRegistry, java.util.function.Function)},
 * so every framework-managed, annotation-based handler inspection sees the same, single composition, regardless of
 * whether it runs in a Spring or plain-Java application.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 4.6.0
 */
public class SpringHandlerDefinitionEnhancer implements ConfigurationEnhancer {

    private final List<HandlerDefinition> handlerDefinitions;
    private final List<HandlerEnhancerDefinition> handlerEnhancerDefinitions;

    /**
     * Constructs a {@code SpringHandlerDefinitionEnhancer} composing the given {@code handlerDefinitions} and
     * {@code handlerEnhancerDefinitions}, typically all {@link HandlerDefinition} and {@link HandlerEnhancerDefinition}
     * beans available in a Spring {@link org.springframework.context.ApplicationContext}.
     *
     * @param handlerDefinitions         the {@link HandlerDefinition} beans to compose into the
     *                                   {@link ComponentRegistry}
     * @param handlerEnhancerDefinitions the {@link HandlerEnhancerDefinition} beans to compose into the
     *                                   {@link ComponentRegistry}
     */
    public SpringHandlerDefinitionEnhancer(List<HandlerDefinition> handlerDefinitions,
                                           List<HandlerEnhancerDefinition> handlerEnhancerDefinitions) {
        this.handlerDefinitions = handlerDefinitions;
        this.handlerEnhancerDefinitions = handlerEnhancerDefinitions;
    }

    @Override
    public void enhance(ComponentRegistry componentRegistry) {
        if (!handlerDefinitions.isEmpty()) {
            HandlerDefinitionUtils.registerToComponentRegistry(
                    componentRegistry, c -> MultiHandlerDefinition.ordered(handlerDefinitions)
            );
        }
        if (!handlerEnhancerDefinitions.isEmpty()) {
            HandlerEnhancerDefinitionUtils.registerToComponentRegistry(
                    componentRegistry, c -> MultiHandlerEnhancerDefinition.ordered(handlerEnhancerDefinitions)
            );
        }
    }
}
