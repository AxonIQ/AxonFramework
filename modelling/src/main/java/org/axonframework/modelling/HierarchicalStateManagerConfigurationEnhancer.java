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

package org.axonframework.modelling;

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;

import java.util.Optional;
import org.axonframework.common.configuration.DecoratorOrder;

/**
 * {@link ConfigurationEnhancer} that registers a decorator for the {@link StateManager} that, when a parent
 * configuration is present, wraps child and parent {@link StateManager} in a {@link HierarchicalStateManager} that
 * delegates to the parent if a state cannot be resolved by the current configuration.
 * <p>
 * To prevent modules not defining entities not being able to find the {@link StateManager} component to access the
 * parent configuration, an empty {@link StateManager} is registered when no {@link StateManager} is present.
 * <p>
 * This enhancer is registered with the highest order, so it will be executed last. This is to ensure that any
 * registered {@link StateManager} is decorated with the {@link HierarchicalStateManager}.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
public class HierarchicalStateManagerConfigurationEnhancer implements ConfigurationEnhancer {

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void enhance(ComponentRegistry componentRegistry) {
        componentRegistry.registerDecorator(
                StateManager.class,
                DecoratorOrder.HIERARCHICAL,
                (config, componentName, component) -> {
                    Optional<StateManager> parentComponent = Optional
                            .ofNullable(config.getParent())
                            .flatMap(p -> p.getOptionalComponent(StateManager.class));
                    if (parentComponent.isPresent()) {
                        return HierarchicalStateManager.create(parentComponent.get(), component);
                    }
                    return component;
                });
    }
}
