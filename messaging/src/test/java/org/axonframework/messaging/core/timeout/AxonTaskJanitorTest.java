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
package org.axonframework.messaging.core.timeout;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.junit.jupiter.api.*;

import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating {@link AxonTaskJanitor#executorComponent()}.
 *
 * @author Steven van Beelen
 */
class AxonTaskJanitorTest {

    private DefaultComponentRegistry componentRegistry;

    @BeforeEach
    void setUp() {
        componentRegistry = new DefaultComponentRegistry();
        componentRegistry.disableEnhancerScanning();
    }

    @Test
    void definesAWorkingScheduledExecutorComponentServiceComponentIndependentFromTheSharedInstance() {
        // given
        componentRegistry.registerIfNotPresent(AxonTaskJanitor.executorComponent());

        // when
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));
        ScheduledExecutorService executor =
                config.getComponent(ScheduledExecutorService.class, AxonTaskJanitor.EXECUTOR_COMPONENT_NAME);

        // then -- a fresh executor is created per Configuration, distinct from the JVM-wide INSTANCE fallback, so
        // shutting one Configuration down can never affect another Configuration's timeout enforcement.
        assertThat(executor).isNotNull().isNotSameAs(AxonTaskJanitor.createExecutor());
        assertThat(executor.isShutdown()).isFalse();
    }

    @Test
    void bothTimeoutEnhancersRegisteringTheExecutorComponentIndependentlyShareTheSameInstance() {
        // given -- both enhancers independently call registerIfNotPresent(AxonTaskJanitor.executor())
        componentRegistry.registerEnhancer(new HandlerTimeoutConfigurationEnhancer());
        componentRegistry.registerEnhancer(new TimeoutUnitOfWorkFactoryConfigurationEnhancer());

        // when
        Configuration config = componentRegistry.build(mock(LifecycleRegistry.class));
        ScheduledExecutorService first =
                config.getComponent(ScheduledExecutorService.class, AxonTaskJanitor.EXECUTOR_COMPONENT_NAME);
        ScheduledExecutorService second =
                config.getComponent(ScheduledExecutorService.class, AxonTaskJanitor.EXECUTOR_COMPONENT_NAME);

        // then -- handler timeouts and unit of work timeouts are enforced on a single, shared executor
        assertThat(first).isNotNull().isNotSameAs(AxonTaskJanitor.createExecutor()).isSameAs(second);
    }
}
