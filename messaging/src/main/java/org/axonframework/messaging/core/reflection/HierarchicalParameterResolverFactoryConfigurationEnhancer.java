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

package org.axonframework.messaging.core.reflection;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.DecoratorOrder;
import org.axonframework.messaging.core.annotation.HierarchicalParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;

import java.util.Optional;

/**
 * {@link ConfigurationEnhancer} that registers a decorator for the {@link ParameterResolverFactory} that, when a parent
 * configuration is present, wraps both the parent and the current {@link ParameterResolverFactory} in a
 * {@link HierarchicalParameterResolverFactory} that delegates to the parent if a parameter cannot be resolved by the
 * current configuration.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 * @see HierarchicalParameterResolverFactory
 */
public class HierarchicalParameterResolverFactoryConfigurationEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry componentRegistry) {
        componentRegistry.registerDecorator(
                ParameterResolverFactory.class,
                // We want this to be executed late, but still allow users to be able to add resolvers
                // after this enhancer. Which would then not be available for child configurations.
                DecoratorOrder.HIERARCHICAL,
                (config, componentName, component) -> {
                    Optional<ParameterResolverFactory> parentComponent = Optional
                            .ofNullable(config.getParent())
                            .flatMap(p -> p.getOptionalComponent(ParameterResolverFactory.class));
                    if (parentComponent.isPresent()) {
                        return HierarchicalParameterResolverFactory.create(
                                parentComponent.get(),
                                component
                        );
                    }
                    return component;
                });
    }
}
