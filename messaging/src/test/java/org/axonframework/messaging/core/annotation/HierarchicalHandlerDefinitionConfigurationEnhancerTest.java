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
import org.axonframework.messaging.core.configuration.reflection.HandlerDefinitionUtils;
import org.axonframework.messaging.core.reflection.HierarchicalHandlerDefinitionConfigurationEnhancer;
import org.axonframework.messaging.core.reflection.HierarchicalHandlerEnhancerDefinitionConfigurationEnhancer;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Optional;

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

    @Test
    void wrapHandlerIsInvokedExactlyOnceForAHandlerDeclaredAtTheOutermostParentAcrossThreeNestedConfigurations()
            throws NoSuchMethodException {
        // given - only the grandparent's HandlerDefinition produces a handler; parent and child both decline
        Method handlerMethod = getClass().getDeclaredMethod("dummyHandlerMethod");
        MessageHandlingMember<Object> handler = mock(MessageHandlingMember.class);

        HandlerDefinition grandparentHandlerDefinition = mock(HandlerDefinition.class);
        when(grandparentHandlerDefinition.createHandler(any(), any(), any(), any()))
                .thenReturn(Optional.of(handler));
        HandlerEnhancerDefinition grandparentEnhancer = mockPassThroughEnhancer();

        HandlerDefinition parentHandlerDefinition = mock(HandlerDefinition.class);
        when(parentHandlerDefinition.createHandler(any(), any(), any(), any())).thenReturn(Optional.empty());
        HandlerEnhancerDefinition parentEnhancer = mockPassThroughEnhancer();

        HandlerDefinition childHandlerDefinition = mock(HandlerDefinition.class);
        when(childHandlerDefinition.createHandler(any(), any(), any(), any())).thenReturn(Optional.empty());
        HandlerEnhancerDefinition childEnhancer = mockPassThroughEnhancer();

        // Register each level the way HandlerDefinitionUtils does in production: the HandlerDefinition component is
        // already a MultiHandlerDefinition wrapping the level's own enhancer before the hierarchical composition
        // (registered by createTestRegistry()) merges it with its parent
        DefaultComponentRegistry grandparent = createTestRegistry();
        grandparent.registerComponent(HandlerEnhancerDefinition.class, c -> grandparentEnhancer);
        HandlerDefinitionUtils.registerToComponentRegistry(grandparent, c -> grandparentHandlerDefinition);

        DefaultComponentRegistry parent = createTestRegistry();
        parent.registerComponent(HandlerEnhancerDefinition.class, c -> parentEnhancer);
        HandlerDefinitionUtils.registerToComponentRegistry(parent, c -> parentHandlerDefinition);

        DefaultComponentRegistry child = createTestRegistry();
        child.registerComponent(HandlerEnhancerDefinition.class, c -> childEnhancer);
        HandlerDefinitionUtils.registerToComponentRegistry(child, c -> childHandlerDefinition);

        Configuration grandparentConfig = grandparent.build(mock(LifecycleRegistry.class));
        Configuration parentConfig = parent.buildNested(grandparentConfig, mock(LifecycleRegistry.class));
        Configuration childConfig = child.buildNested(parentConfig, mock(LifecycleRegistry.class));

        HandlerDefinition composed = childConfig.getComponent(HandlerDefinition.class);

        // when
        composed.createHandler(Object.class, handlerMethod, mock(ParameterResolverFactory.class), message -> null);

        // then - the grandparent-declared handler is wrapped exactly once by every enhancer in the hierarchy.
        // If MultiHandlerDefinition ever stopped flattening nested delegates, the parent and grandparent enhancers
        // would be invoked twice: once by their own (now nested) MultiHandlerDefinition and once more by the child's
        verify(grandparentEnhancer, times(1)).wrapHandler(any());
        verify(parentEnhancer, times(1)).wrapHandler(any());
        verify(childEnhancer, times(1)).wrapHandler(any());
    }

    private static HandlerEnhancerDefinition mockPassThroughEnhancer() {
        HandlerEnhancerDefinition enhancer = mock(HandlerEnhancerDefinition.class);
        when(enhancer.wrapHandler(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return enhancer;
    }

    @SuppressWarnings("unused")
    private void dummyHandlerMethod() {
        // Used purely as a reflection target for HandlerDefinition#createHandler in the test above
    }

    private DefaultComponentRegistry createTestRegistry() {
        DefaultComponentRegistry componentRegistry = new DefaultComponentRegistry();
        componentRegistry.disableEnhancerScanning()
                         .registerEnhancer(new HierarchicalHandlerDefinitionConfigurationEnhancer())
                         .registerEnhancer(new HierarchicalHandlerEnhancerDefinitionConfigurationEnhancer());
        return componentRegistry;
    }
}
