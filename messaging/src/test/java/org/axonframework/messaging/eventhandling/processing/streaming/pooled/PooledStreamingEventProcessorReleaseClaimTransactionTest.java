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

package org.axonframework.messaging.eventhandling.processing.streaming.pooled;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.AsyncInMemoryStreamableEventSource;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SegmentChangeListener;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PooledStreamingEventProcessorReleaseClaimTransactionTest {

    private static final String PROCESSOR_NAME = "release-claim-processor";
    private static final String LISTENER_THREAD_NAME = "release-listener";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ThreadLocal<Boolean> transactionBoundToThread = ThreadLocal.withInitial(() -> false);
    private final TransactionCheckingTokenStore tokenStore = new TransactionCheckingTokenStore();
    private final ExecutorService listenerExecutor =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, LISTENER_THREAD_NAME));
    private @Nullable AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
        listenerExecutor.shutdownNow();
    }

    @Nested
    class WhenReleasingASegment {

        @Test
        void releaseClaimRunsInTheTransactionWithASynchronousListener() {
            // given
            start(SegmentChangeListener.runOnRelease(segment -> {
            }), 5000);

            // when
            processor().releaseSegment(0);

            // then
            await().atMost(TIMEOUT).until(() -> !tokenStore.releaseAttempts.isEmpty());
            assertThat(tokenStore.releaseAttempts).allMatch(ReleaseAttempt::transactionBound);
        }

        @Test
        void releaseClaimRunsInTheTransactionWhenAListenerCompletesOnAnotherThread() {
            // given a release listener that finishes its work a moment later, on its own thread
            start(SegmentChangeListener.onRelease(segment -> CompletableFuture.runAsync(
                    () -> {
                    },
                    CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS, listenerExecutor)
            )), 5000);

            // when
            processor().releaseSegment(0);

            // then
            await().atMost(TIMEOUT).until(() -> !tokenStore.releaseAttempts.isEmpty());
            assertThat(tokenStore.releaseAttempts)
                    .as("the claim release must run in the transaction of the release")
                    .allMatch(ReleaseAttempt::transactionBound);
        }

        @Test
        void releaseClaimRunsInTheTransactionWhenAListenerExceedsTheClaimExtensionThreshold() {
            // given a release listener that never finishes, and a short claim extension threshold
            start(SegmentChangeListener.onRelease(segment -> new CompletableFuture<>()), 200);

            // when
            processor().releaseSegment(0);

            // then
            await().atMost(TIMEOUT).until(() -> !tokenStore.releaseAttempts.isEmpty());
            assertThat(tokenStore.releaseAttempts)
                    .as("the claim release must run in the transaction of the release")
                    .allMatch(ReleaseAttempt::transactionBound);
        }
    }

    private void start(SegmentChangeListener releaseListener, long claimExtensionThreshold) {
        var eventSource = new AsyncInMemoryStreamableEventSource(false, false);
        var unitOfWorkFactory = new TransactionalUnitOfWorkFactory(threadBoundTransactionManager(),
                                                                   UnitOfWorkTestUtils.SIMPLE_FACTORY);
        var module = EventProcessorModule.pooledStreaming(PROCESSOR_NAME)
                                         .eventHandlingComponents(c -> c.autodetected("handler",
                                                                                      cfg -> new Handler()))
                                         .customized((cfg, c) -> c.eventSource(eventSource)
                                                                  .tokenStore(tokenStore)
                                                                  .unitOfWorkFactory(unitOfWorkFactory)
                                                                  .initialSegmentCount(1)
                                                                  .claimExtensionThreshold(claimExtensionThreshold)
                                                                  .addSegmentChangeListener(releaseListener));
        configuration = MessagingConfigurer.create()
                                           .eventProcessing(ep -> ep.pooledStreaming(ps -> ps.processor(module)))
                                           .build();
        configuration.start();
        await().atMost(TIMEOUT).until(() -> processor().processingStatus().containsKey(0));
    }

    private PooledStreamingEventProcessor processor() {
        return (PooledStreamingEventProcessor) configuration.getComponents(EventProcessor.class).get(PROCESSOR_NAME);
    }

    private TransactionManager threadBoundTransactionManager() {
        return new TransactionManager() {
            @Override
            public Transaction startTransaction() {
                transactionBoundToThread.set(true);
                return new Transaction() {
                    @Override
                    public void commit() {
                        transactionBoundToThread.set(false);
                    }

                    @Override
                    public void rollback() {
                        transactionBoundToThread.set(false);
                    }
                };
            }

            @Override
            public boolean requiresSameThreadInvocations() {
                return true;
            }
        };
    }

    private record ReleaseAttempt(String threadName, boolean transactionBound) {

    }

    private class TransactionCheckingTokenStore extends InMemoryTokenStore {

        private final List<ReleaseAttempt> releaseAttempts = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Void> releaseClaim(String processorName,
                                                    int segment,
                                                    @Nullable ProcessingContext context) {
            boolean bound = transactionBoundToThread.get();
            releaseAttempts.add(new ReleaseAttempt(Thread.currentThread().getName(), bound));
            if (!bound) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "No transaction bound to thread [" + Thread.currentThread().getName() + "]"
                ));
            }
            return super.releaseClaim(processorName, segment, context);
        }
    }

    private static class Handler {

        @EventHandler
        void on(String event) {
        }
    }
}
