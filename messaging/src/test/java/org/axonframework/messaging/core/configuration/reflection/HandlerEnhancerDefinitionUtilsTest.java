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
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link HandlerEnhancerDefinitionUtils}.
 *
 * @author Steven van Beelen
 */
class HandlerEnhancerDefinitionUtilsTest {

    private DefaultComponentRegistry componentRegistry;

    @BeforeEach
    void setUp() {
        componentRegistry = new DefaultComponentRegistry();
        componentRegistry.disableEnhancerScanning();
    }

    @Test
    void registersHandlerEnhancerDefinitionAsComponentIfNoneKnown() {
        // given...
        HandlerEnhancerDefinition testEnhancer = Mockito.mock(HandlerEnhancerDefinition.class);
        HandlerEnhancerDefinitionUtils.registerToComponentRegistry(componentRegistry, c -> testEnhancer);
        Configuration config = componentRegistry.build(Mockito.mock(LifecycleRegistry.class));
        // when...
        HandlerEnhancerDefinition result = config.getComponent(HandlerEnhancerDefinition.class);
        // then...
        assertThat(result).isEqualTo(testEnhancer);
    }

    @Test
    void registersHandlerEnhancerDefinitionAsDecoratorWithMultiHandlerEnhancerDefinitionIfAlreadyHas() {
        // given...
        HandlerEnhancerDefinition testEnhancerOne = Mockito.mock(HandlerEnhancerDefinition.class);
        HandlerEnhancerDefinition testEnhancerTwo = Mockito.mock(HandlerEnhancerDefinition.class);
        HandlerEnhancerDefinitionUtils.registerToComponentRegistry(componentRegistry, c -> testEnhancerOne);
        HandlerEnhancerDefinitionUtils.registerToComponentRegistry(componentRegistry, c -> testEnhancerTwo);
        Configuration config = componentRegistry.build(Mockito.mock(LifecycleRegistry.class));
        // when...
        HandlerEnhancerDefinition result = config.getComponent(HandlerEnhancerDefinition.class);
        // then...
        assertThat(result).isInstanceOf(MultiHandlerEnhancerDefinition.class);
        List<HandlerEnhancerDefinition> resultDelegateDefinitions =
                ((MultiHandlerEnhancerDefinition) result).getDelegates();
        assertThat(resultDelegateDefinitions).contains(testEnhancerOne);
        assertThat(resultDelegateDefinitions).contains(testEnhancerTwo);
    }
}
