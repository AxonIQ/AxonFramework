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

package org.axonframework.messaging.core.configuration.reflection;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link HandlerDefinitionUtils}.
 *
 * @author Steven van Beelen
 */
class HandlerDefinitionUtilsTest {

    private DefaultComponentRegistry componentRegistry;
    private HandlerEnhancerDefinition handlerEnhancerDefinition;

    @BeforeEach
    void setUp() {
        componentRegistry = new DefaultComponentRegistry();
        componentRegistry.disableEnhancerScanning();
        handlerEnhancerDefinition = Mockito.mock(HandlerEnhancerDefinition.class);
        componentRegistry.registerComponent(HandlerEnhancerDefinition.class, c -> handlerEnhancerDefinition);
    }

    @Test
    void registersHandlerDefinitionAsComponentIfNoneKnown() {
        // given...
        HandlerDefinition testDefinition = Mockito.mock(HandlerDefinition.class);
        HandlerDefinitionUtils.registerToComponentRegistry(componentRegistry, c -> testDefinition);
        Configuration config = componentRegistry.build(Mockito.mock(LifecycleRegistry.class));
        // when...
        HandlerDefinition result = config.getComponent(HandlerDefinition.class);
        // then...
        assertThat(result).isInstanceOf(MultiHandlerDefinition.class);
        assertThat(((MultiHandlerDefinition) result).getDelegates()).contains(testDefinition);
    }

    @Test
    void registersHandlerDefinitionAsDecoratorWithMultiHandlerDefinitionIfAlreadyHas() {
        // given...
        HandlerDefinition testDefinitionOne = Mockito.mock(HandlerDefinition.class);
        HandlerDefinition testDefinitionTwo = Mockito.mock(HandlerDefinition.class);
        HandlerDefinitionUtils.registerToComponentRegistry(componentRegistry, c -> testDefinitionOne);
        HandlerDefinitionUtils.registerToComponentRegistry(componentRegistry, c -> testDefinitionTwo);
        Configuration config = componentRegistry.build(Mockito.mock(LifecycleRegistry.class));
        // when...
        HandlerDefinition result = config.getComponent(HandlerDefinition.class);
        // then...
        assertThat(result).isInstanceOf(MultiHandlerDefinition.class);
        List<HandlerDefinition> resultDelegateDefinitions = ((MultiHandlerDefinition) result).getDelegates();
        assertThat(resultDelegateDefinitions).contains(testDefinitionOne);
        assertThat(resultDelegateDefinitions).contains(testDefinitionTwo);
    }

    @Test
    void assembledHandlerDefinitionUsesComposedHandlerEnhancerDefinitionComponent() {
        // given...
        HandlerDefinition testDefinition = Mockito.mock(HandlerDefinition.class);
        HandlerDefinitionUtils.registerToComponentRegistry(componentRegistry, c -> testDefinition);
        Configuration config = componentRegistry.build(Mockito.mock(LifecycleRegistry.class));
        // when...
        HandlerDefinition result = config.getComponent(HandlerDefinition.class);
        // then...
        assertThat(result).isInstanceOf(MultiHandlerDefinition.class);
        HandlerEnhancerDefinition resultEnhancer = ((MultiHandlerDefinition) result).getHandlerEnhancerDefinition();
        assertThat(resultEnhancer).isInstanceOf(MultiHandlerEnhancerDefinition.class);
        assertThat(((MultiHandlerEnhancerDefinition) resultEnhancer).getDelegates())
                .contains(handlerEnhancerDefinition);
    }
}
