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

package org.axonframework.hunt.scenario;

import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressContext;
import org.axonframework.messaging.eventhandling.processing.streaming.progress.SegmentProgressStrategy;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The one running pooled processor the progress-persistence probes share, and nothing else.
 * <p>
 * The probes in this package all need the same thing: a {@link PooledStreamingEventProcessor} over an in-heap event
 * store, a caller-supplied {@link TokenStore} and a caller-supplied {@link SegmentProgressStrategy}, started, and
 * closed together with the two executors it owns. This class is that harness, extracted so each probe carries only
 * the strategy and the assertions that make it a probe.
 */
final class ProgressProbeSupport {

    private ProgressProbeSupport() {
    }

    /**
     * The probes resolve no components: none of them resets a processor, which is the one thing that needs a
     * converter.
     */
    static final ApplicationContext NO_COMPONENTS = new ApplicationContext() {
        @Override
        public <C> C component(Class<C> type, @Nullable String name) {
            throw new UnsupportedOperationException(
                    "The progress probe provides no component of type [" + type.getName() + "].");
        }
    };

    static long storedPosition(TokenStore tokenStore, String processorName, int segmentId) {
        TrackingToken token = tokenStore.fetchToken(processorName, segmentId, null)
                                        .orTimeout(30, TimeUnit.SECONDS)
                                        .join();
        return token == null ? -1L : token.position().orElse(-1L);
    }

    /**
     * One running processor and the two executors it owns, closed together.
     */
    record Harness(PooledStreamingEventProcessor processor,
                   ScheduledExecutorService coordinator,
                   ScheduledExecutorService worker) implements AutoCloseable {

        static Harness start(String processorName,
                             TokenStore tokenStore,
                             Function<SegmentProgressContext, SegmentProgressStrategy> strategy,
                             long beatMillis) {
            EventStore eventStore = new StorageEngineBackedEventStore(new InMemoryEventStorageEngine(),
                                                                      new SimpleEventBus(),
                                                                      event -> Set.of());
            ScheduledExecutorService coordinator = new ScheduledThreadPoolExecutor(1, daemon("probe-coordinator"));
            ScheduledExecutorService worker = new ScheduledThreadPoolExecutor(1, daemon("probe-worker"));
            EventHandlingComponent component = SimpleEventHandlingComponent.create(processorName + "-component");
            PooledStreamingEventProcessorConfiguration configuration =
                    new PooledStreamingEventProcessorConfiguration(new EventProcessorConfiguration(processorName, null))
                            .eventSource(eventStore)
                            .tokenStore(tokenStore)
                            .unitOfWorkFactory(new SimpleUnitOfWorkFactory(NO_COMPONENTS))
                            .coordinatorExecutor(coordinator)
                            .workerExecutor(worker)
                            .initialSegmentCount(1)
                            .tokenClaimInterval(beatMillis)
                            .claimExtensionThreshold(beatMillis)
                            .batchSize(1)
                            .progressStrategyFactoryBuilder(components -> strategy::apply);
            PooledStreamingEventProcessor processor =
                    new PooledStreamingEventProcessor(processorName, List.of(component), configuration);
            processor.start().orTimeout(30, TimeUnit.SECONDS).join();
            return new Harness(processor, coordinator, worker);
        }

        @Override
        public void close() {
            try {
                processor.shutdown().orTimeout(30, TimeUnit.SECONDS).join();
            } catch (RuntimeException e) {
                // A processor that will not stop is a finding about the processor, not a reason to leak the threads.
            }
            coordinator.shutdownNow();
            worker.shutdownNow();
        }

        private static ThreadFactory daemon(String prefix) {
            AtomicInteger counter = new AtomicInteger();
            return runnable -> {
                Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            };
        }
    }
}
