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

import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition;

import java.util.Optional;

/**
 * {@link ConfigurationEnhancer} that registers a decorator for the {@link HandlerEnhancerDefinition} that, when a
 * {@link Configuration#getParent() parent configuration} is present, composes both the parent and the current
 * {@code HandlerEnhancerDefinition} into a {@link MultiHandlerEnhancerDefinition}.
 * <p>
 * Without this enhancer, a {@code HandlerEnhancerDefinition} registered at a parent {@code Configuration} (e.g. a
 * top-level {@code MessagingConfigurer}) would not be visible to a module's own, separate {@code ComponentRegistry}
 * (e.g. an {@code EventSourcedEntityModule}), since every module resolves its own {@code HandlerEnhancerDefinition}
 * component independently.
 *
 * @author Steven van Beelen
 * @see MultiHandlerEnhancerDefinition
 * @see org.axonframework.messaging.core.configuration.reflection.HandlerEnhancerDefinitionUtils
 * @since 5.3.2
 */
@RegistrationScope("Register the decorator once at the root; do not re-invoke in child module registries. The "
        + "decorator function resolves config.getParent() lazily, at each module's own resolution time, so the single "
        + "DecoratorDefinition copied down on its own already composes every module correctly with its own parent. "
        + "Re-invoking per nesting level would register the decorator again, composing the parent's contribution "
        + "into a module's own HandlerEnhancerDefinition twice.")
public class HierarchicalHandlerEnhancerDefinitionConfigurationEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry componentRegistry) {
        componentRegistry.registerDecorator(
                HandlerEnhancerDefinition.class,
                // We want this to be executed late, but still allow users to be able to add enhancers
                // after this enhancer. Which would then not be available for child configurations.
                Integer.MAX_VALUE >> 1,
                (config, componentName, component) ->
                        Optional.ofNullable(config.getParent())
                                .flatMap(parentConfig -> parentConfig.getOptionalComponent(
                                        HandlerEnhancerDefinition.class
                                ))
                                .map(parentComponent -> (HandlerEnhancerDefinition) MultiHandlerEnhancerDefinition.ordered(
                                        component, parentComponent
                                ))
                                .orElse(component));
    }
}
