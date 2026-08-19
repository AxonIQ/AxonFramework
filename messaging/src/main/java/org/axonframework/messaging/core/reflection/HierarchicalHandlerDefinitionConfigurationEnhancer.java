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
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerDefinition;

import java.util.Optional;

/**
 * {@link ConfigurationEnhancer} that registers a decorator for the {@link HandlerDefinition} that, when a
 * {@link Configuration#getParent() parent configuration} is present, composes both the parent and the current
 * {@code HandlerDefinition} into a {@link MultiHandlerDefinition}, re-assembled with the current, fully composed
 * {@link HandlerEnhancerDefinition}.
 * <p>
 * Without this enhancer, a {@code HandlerDefinition} registered at a parent {@code Configuration} (e.g. a top-level
 * {@code MessagingConfigurer}) would not be visible to a module's own, separate {@code ComponentRegistry} (e.g. an
 * {@code EventSourcedEntityModule}), since every module resolves its own {@code HandlerDefinition} component
 * independently.
 *
 * @author Steven van Beelen
 * @see MultiHandlerDefinition
 * @see org.axonframework.messaging.core.configuration.reflection.HandlerDefinitionUtils
 * @since 5.3.2
 */
public class HierarchicalHandlerDefinitionConfigurationEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry componentRegistry) {
        componentRegistry.registerDecorator(
                HandlerDefinition.class,
                // We want this to be executed late, but still allow users to be able to add definitions
                // after this enhancer. Which would then not be available for child configurations.
                Integer.MAX_VALUE >> 1,
                (config, componentName, component) ->
                        Optional.ofNullable(config.getParent())
                                .flatMap(parentConfig -> parentConfig.getOptionalComponent(HandlerDefinition.class))
                                .map(parentComponent -> (HandlerDefinition) MultiHandlerDefinition.ordered(
                                        config.getComponent(HandlerEnhancerDefinition.class),
                                        component,
                                        parentComponent
                                ))
                                .orElse(component));
    }
}
