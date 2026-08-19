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
import org.axonframework.messaging.core.reflection.ClasspathHandlerDefinitionConfigurationEnhancer;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link ClasspathHandlerDefinitionConfigurationEnhancer}.
 *
 * @author Steven van Beelen
 */
class ClasspathHandlerDefinitionConfigurationEnhancerTest {

    private DefaultComponentRegistry componentRegistry;

    @BeforeEach
    void setUp() {
        componentRegistry = new DefaultComponentRegistry();
        componentRegistry.disableEnhancerScanning();
    }

    @Test
    void setsClasspathHandlerDefinitionAsComponent() {
        // given...
        componentRegistry.registerEnhancer(new ClasspathHandlerDefinitionConfigurationEnhancer());
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));
        // when...
        HandlerDefinition result = config.getComponent(HandlerDefinition.class);
        // then...
        assertThat(result).isInstanceOf(MultiHandlerDefinition.class);
    }

    @Test
    void setsClasspathHandlerEnhancerDefinitionAsComponent() {
        // given...
        componentRegistry.registerEnhancer(new ClasspathHandlerDefinitionConfigurationEnhancer());
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));
        // when...
        HandlerEnhancerDefinition result = config.getComponent(HandlerEnhancerDefinition.class);
        // then...
        assertThat(result).isInstanceOf(MultiHandlerEnhancerDefinition.class);
    }

    @Test
    void composesWithAlreadyRegisteredHandlerDefinitionAndHandlerEnhancerDefinition() {
        // given...
        HandlerDefinition customDefinition = mock(HandlerDefinition.class);
        HandlerEnhancerDefinition customEnhancer = mock(HandlerEnhancerDefinition.class);
        componentRegistry.registerComponent(HandlerDefinition.class, c -> customDefinition)
                         .registerComponent(HandlerEnhancerDefinition.class, c -> customEnhancer)
                         .registerEnhancer(new ClasspathHandlerDefinitionConfigurationEnhancer());
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));
        // when...
        HandlerDefinition resultDefinition = config.getComponent(HandlerDefinition.class);
        HandlerEnhancerDefinition resultEnhancer = config.getComponent(HandlerEnhancerDefinition.class);
        // then...
        assertThat(resultDefinition).isInstanceOf(MultiHandlerDefinition.class);
        assertThat(((MultiHandlerDefinition) resultDefinition).getDelegates()).contains(customDefinition);
        assertThat(resultEnhancer).isInstanceOf(MultiHandlerEnhancerDefinition.class);
        assertThat(((MultiHandlerEnhancerDefinition) resultEnhancer).getDelegates()).contains(customEnhancer);
    }
}
