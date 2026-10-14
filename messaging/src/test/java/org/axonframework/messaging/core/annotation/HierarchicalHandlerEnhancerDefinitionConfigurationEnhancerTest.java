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
import org.axonframework.messaging.core.reflection.HierarchicalHandlerEnhancerDefinitionConfigurationEnhancer;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link HierarchicalHandlerEnhancerDefinitionConfigurationEnhancer}.
 *
 * @author Steven van Beelen
 */
class HierarchicalHandlerEnhancerDefinitionConfigurationEnhancerTest {

    @Test
    void composesParentAndChildHandlerEnhancerDefinitionIntoMultiHandlerEnhancerDefinition() {
        // Set up a parent with a unique HandlerEnhancerDefinition
        DefaultComponentRegistry parent = createTestRegistry();
        HandlerEnhancerDefinition parentEnhancer = mock(HandlerEnhancerDefinition.class);
        parent.registerComponent(HandlerEnhancerDefinition.class, c -> parentEnhancer);

        // Set up the child with its own HandlerEnhancerDefinition
        DefaultComponentRegistry child = createTestRegistry();
        HandlerEnhancerDefinition childEnhancer = mock(HandlerEnhancerDefinition.class);
        child.registerComponent(HandlerEnhancerDefinition.class, c -> childEnhancer);

        // Build both in a nested way - this will compose parent and child into a MultiHandlerEnhancerDefinition
        Configuration parentConfig = parent.build(mock(LifecycleRegistry.class));
        Configuration childConfig = child.buildNested(parentConfig, mock(LifecycleRegistry.class));

        // So, we can assert the right enhancers are composed
        HandlerEnhancerDefinition parentResult = parentConfig.getComponent(HandlerEnhancerDefinition.class);
        HandlerEnhancerDefinition childResult = childConfig.getComponent(HandlerEnhancerDefinition.class);
        assertThat(parentResult).isSameAs(parentEnhancer);
        assertThat(childResult).isInstanceOf(MultiHandlerEnhancerDefinition.class);
        assertThat(((MultiHandlerEnhancerDefinition) childResult).getDelegates())
                .contains(parentEnhancer, childEnhancer);
    }

    @Test
    void returnsOwnHandlerEnhancerDefinitionUnchangedWhenNoParentIsPresent() {
        DefaultComponentRegistry registry = createTestRegistry();
        HandlerEnhancerDefinition enhancer = mock(HandlerEnhancerDefinition.class);
        registry.registerComponent(HandlerEnhancerDefinition.class, c -> enhancer);

        Configuration config = registry.build(mock(LifecycleRegistry.class));

        assertThat(config.getComponent(HandlerEnhancerDefinition.class)).isSameAs(enhancer);
    }

    private DefaultComponentRegistry createTestRegistry() {
        DefaultComponentRegistry defaultComponentRegistry = new DefaultComponentRegistry();
        defaultComponentRegistry.disableEnhancerScanning()
                                .registerEnhancer(new HierarchicalHandlerEnhancerDefinitionConfigurationEnhancer());
        return defaultComponentRegistry;
    }
}
