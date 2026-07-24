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

package org.axonframework.modelling.tracing.configuration;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.tracing.TracingStateManager;
import org.axonframework.modelling.configuration.ModellingConfigurer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModellingTracingConfigurationEnhancerTest {

    private final TestSpanFactory spanFactory = new TestSpanFactory();
    private AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Nested
    class StateManagerDecoration {

        @Test
        void decoratesStateManagerWhenSpanFactoryPresentAndTracingEnabled() {
            // given a configuration with a SpanFactory and the default (enabled) settings
            configuration = ModellingConfigurer.create()
                                               .componentRegistry(registry -> registry.registerComponent(
                                                       SpanFactory.class, c -> spanFactory))
                                               .build();

            // when
            StateManager stateManager = configuration.getComponent(StateManager.class);

            // then
            assertThat(stateManager).isInstanceOf(TracingStateManager.class);
        }

        @Test
        void leavesStateManagerUndecoratedWhenNoSpanFactoryConfigured() {
            // given a configuration without a SpanFactory (tracing disabled)
            configuration = ModellingConfigurer.create().build();

            // when
            StateManager stateManager = configuration.getComponent(StateManager.class);

            // then
            assertThat(stateManager).isNotInstanceOf(TracingStateManager.class);
        }

        @Test
        void leavesStateManagerUndecoratedWhenStateManagerTracingIsDisabled() {
            // given a SpanFactory but the state-manager toggle disabled
            configuration = ModellingConfigurer.create()
                                               .componentRegistry(registry -> {
                                                   registry.registerComponent(SpanFactory.class, c -> spanFactory);
                                                   registry.registerComponent(
                                                           ModellingTracingSettings.class,
                                                           c -> new ModellingTracingSettings(
                                                                   true,
                                                                   false,
                                                                   ModellingTracingSettings.SpanAttributesProviders
                                                                           .enabledByDefault()));
                                               })
                                               .build();

            // when
            StateManager stateManager = configuration.getComponent(StateManager.class);

            // then
            assertThat(stateManager).isNotInstanceOf(TracingStateManager.class);
        }
    }
}
