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

package org.axonframework.messaging.core.annotation;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.messaging.core.reflection.HierarchicalHandlerDefinitionConfigurationEnhancer;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link HierarchicalHandlerDefinitionConfigurationEnhancer}.
 *
 * @author Steven van Beelen
 */
class HierarchicalHandlerDefinitionConfigurationEnhancerTest {

    @Test
    void composesParentAndChildHandlerDefinitionIntoMultiHandlerDefinition() {
        // Set up a parent with a unique HandlerDefinition
        DefaultComponentRegistry parent = createTestRegistry();
        HandlerDefinition parentHandlerDefinition = mock(HandlerDefinition.class);
        parent.registerComponent(HandlerDefinition.class, c -> parentHandlerDefinition);
        parent.registerComponent(HandlerEnhancerDefinition.class, c -> mock(HandlerEnhancerDefinition.class));

        // Set up the child with its own HandlerDefinition
        DefaultComponentRegistry child = createTestRegistry();
        HandlerDefinition childHandlerDefinition = mock(HandlerDefinition.class);
        child.registerComponent(HandlerDefinition.class, c -> childHandlerDefinition);
        child.registerComponent(HandlerEnhancerDefinition.class, c -> mock(HandlerEnhancerDefinition.class));

        // Build both in a nested way - this will compose parent and child into a MultiHandlerDefinition
        Configuration parentConfig = parent.build(mock(LifecycleRegistry.class));
        Configuration childConfig = child.buildNested(parentConfig, mock(LifecycleRegistry.class));

        // So, we can assert the right definitions are composed
        HandlerDefinition parentResult = parentConfig.getComponent(HandlerDefinition.class);
        HandlerDefinition childResult = childConfig.getComponent(HandlerDefinition.class);
        assertThat(parentResult).isSameAs(parentHandlerDefinition);
        assertThat(childResult).isInstanceOf(MultiHandlerDefinition.class);
        assertThat(((MultiHandlerDefinition) childResult).getDelegates())
                .contains(parentHandlerDefinition, childHandlerDefinition);
    }

    @Test
    void returnsOwnHandlerDefinitionUnchangedWhenNoParentIsPresent() {
        DefaultComponentRegistry registry = createTestRegistry();
        HandlerDefinition handlerDefinition = mock(HandlerDefinition.class);
        registry.registerComponent(HandlerDefinition.class, c -> handlerDefinition);

        Configuration config = registry.build(mock(LifecycleRegistry.class));

        assertThat(config.getComponent(HandlerDefinition.class)).isSameAs(handlerDefinition);
    }

    private DefaultComponentRegistry createTestRegistry() {
        DefaultComponentRegistry componentRegistry = new DefaultComponentRegistry();
        componentRegistry.disableEnhancerScanning()
                         .registerEnhancer(new HierarchicalHandlerDefinitionConfigurationEnhancer());
        return componentRegistry;
    }
}
