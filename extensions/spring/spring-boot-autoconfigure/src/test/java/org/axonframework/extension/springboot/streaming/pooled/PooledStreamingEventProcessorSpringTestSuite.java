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

package org.axonframework.extension.springboot.streaming.pooled;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorTestSuite;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Spring Boot variant of {@link PooledStreamingEventProcessorTestSuite}.
 * <p>
 * Creates a fresh {@link PooledStreamingEventProcessor} per test using components obtained from the Spring
 * {@link AxonConfiguration}. Each processor gets a unique UUID-based name, providing natural token store isolation
 * between tests without requiring context teardown or schema cleanup.
 *
 * @since 5.1.1
 */
public abstract class PooledStreamingEventProcessorSpringTestSuite extends PooledStreamingEventProcessorTestSuite {

    @Autowired
    private AxonConfiguration axonConfiguration;

    private ScheduledExecutorService coordinatorExecutor;
    private ScheduledExecutorService workerExecutor;

    @Override
    protected PooledStreamingEventProcessor buildProcessor() {
        String processorName = "PSEPTest-" + UUID.randomUUID();
        coordinatorExecutor = Executors.newSingleThreadScheduledExecutor();
        workerExecutor = Executors.newScheduledThreadPool(4);

        var eventSource = axonConfiguration.getComponent(StreamableEventSource.class);
        var tokenStore = axonConfiguration.getComponent(TokenStore.class);
        var unitOfWorkFactory = axonConfiguration.getComponent(UnitOfWorkFactory.class);

        var config = new PooledStreamingEventProcessorConfiguration(
                new EventProcessorConfiguration(processorName, null))
                .unitOfWorkFactory(unitOfWorkFactory)
                .eventSource(eventSource)
                .tokenStore(tokenStore)
                .initialSegmentCount(1)
                .coordinatorExecutor(coordinatorExecutor)
                .workerExecutor(workerExecutor);

        return new PooledStreamingEventProcessor(
                processorName,
                List.of(SimpleEventHandlingComponent.create(processorName)),
                config
        );
    }

    @Override
    protected void afterProcessorShutdown() {
        if (coordinatorExecutor != null) {
            coordinatorExecutor.shutdownNow();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
    }
}
