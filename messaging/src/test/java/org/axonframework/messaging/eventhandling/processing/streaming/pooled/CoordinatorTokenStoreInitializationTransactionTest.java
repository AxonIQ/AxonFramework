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

import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.axonframework.messaging.eventstreaming.TrackingTokenSource;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for <a href="https://github.com/AxonIQ/AxonFramework/issues/4632">issue #4632</a>, driving the real
 * {@link Coordinator}. It must initialize its token segments even when the initial {@link TrackingToken} resolves on a
 * foreign thread, as with the Axon Server connector (token futures complete on a gRPC callback thread). The
 * collaborators model the failing contract: a {@link TransactionManager} that requires same-thread invocations and
 * binds its transaction to the starting thread, and a {@link TokenStore} whose {@code initializeTokenSegments} fails
 * when run without a bound transaction (like Spring's shared {@code EntityManager}).
 */
class CoordinatorTokenStoreInitializationTransactionTest {

    private static final String PROCESSOR_NAME = "fresh-processor";
    private static final String GRPC_THREAD_NAME = "axon-server-grpc-callback";
    private static final String COORDINATOR_THREAD_NAME = "coordinator-executor";

    private final ThreadLocal<Boolean> transactionBoundToThread = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean transactionBoundDuringInitialize = new AtomicBoolean(false);
    private final AtomicReference<String> initializeThread = new AtomicReference<>();

    private ExecutorService grpcCallbackExecutor;
    private ScheduledExecutorService executorService;
    private Coordinator coordinator;

    @BeforeEach
    void setUp() {
        grpcCallbackExecutor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, GRPC_THREAD_NAME));

        // Faithful double of SpringTransactionManager: thread-bound transaction + same-thread-invocation requirement.
        UnitOfWorkFactory unitOfWorkFactory = getUnitOfWorkFactory();

        // Fresh token store: no segments yet, so the initialization path runs. The persist requires a transaction
        // bound to the *current* thread, exactly like Spring's shared EntityManager.
        TokenStore tokenStore = mock(TokenStore.class);
        when(tokenStore.fetchSegments(eq(PROCESSOR_NAME), any())).thenReturn(completedFuture(List.of()));
        doAnswer(invocation -> {
            boolean bound = transactionBoundToThread.get();
            transactionBoundDuringInitialize.set(bound);
            initializeThread.set(Thread.currentThread().getName());
            if (!bound) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "No transaction bound to thread [" + Thread.currentThread().getName()
                                + "] - cannot reliably process 'persist' call"));
            }
            return completedFuture(List.of(Segment.ROOT_SEGMENT));
        }).when(tokenStore).initializeTokenSegments(eq(PROCESSOR_NAME), anyInt(), any(), any());

        // The Axon Server connector resolves the initial token over gRPC and completes its future on the callback
        // thread. The 50ms delay guarantees the thenCompose continuation is registered before completion, so it runs
        // on the callback thread.
        Function<TrackingTokenSource, CompletableFuture<TrackingToken>> initialToken = source ->
                CompletableFuture.supplyAsync(
                        () -> new GlobalSequenceTrackingToken(0),
                        CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS, grpcCallbackExecutor));

        // Real scheduler for the token-store-init retry loop, but the asynchronous CoordinationTask is suppressed:
        // this test only exercises token-store initialization, not event coordination. Its worker thread carries a
        // recognizable name so the persist can be asserted to run here, on the coordinator's executor, and never on
        // the gRPC callback thread.
        executorService = new ScheduledThreadPoolExecutor(1, runnable -> new Thread(runnable, COORDINATOR_THREAD_NAME)) {
            @Override
            public @NonNull Future<?> submit(@NonNull Runnable task) {
                return CompletableFuture.completedFuture(null);
            }
        };

        StreamableEventSource eventSource = mock(StreamableEventSource.class);
        WorkPackage workPackage = mock(WorkPackage.class);

        coordinator = Coordinator.builder()
                                 .name(PROCESSOR_NAME)
                                 .eventSource(eventSource)
                                 .tokenStore(tokenStore)
                                 .unitOfWorkFactory(unitOfWorkFactory)
                                 .executorService(executorService)
                                 .workPackageFactory((segment, trackingToken) -> workPackage)
                                 .processingStatusUpdater((id, updater) -> {})
                                 .initialToken(initialToken)
                                 .initialSegmentCount(1)
                                 .tokenStoreInitRetryInterval(5)
                                 .tokenStoreInitMaxRetries(2)
                                 .maxSegmentProvider(name -> 1)
                                 .build();
    }

    private @NonNull UnitOfWorkFactory getUnitOfWorkFactory() {
        TransactionManager transactionManager = new TransactionManager() {
            @Override
            public @NonNull Transaction startTransaction() {
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
        return new TransactionalUnitOfWorkFactory(
                transactionManager, new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));
    }

    @AfterEach
    void tearDown() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (grpcCallbackExecutor != null) {
            grpcCallbackExecutor.shutdownNow();
        }
    }

    @Test
    void startInitializesTokenStoreWhenInitialTokenResolvesOnForeignThread() {
        // given a fresh token store and an initial-token source completing on the gRPC callback thread (see setUp)

        // when starting the processor
        CompletableFuture<Void> started = coordinator.start();

        // then initialization completes successfully and the persist observed a bound transaction. The persist is
        // deliberately dispatched to the coordinator's executor (a thread we control), which keeps it off the
        // connector's gRPC callback thread and co-locates begin, persist, and commit on the transaction-owning thread.
        assertThatCode(() -> started.orTimeout(5, TimeUnit.SECONDS).join())
                .doesNotThrowAnyException();
        assertThat(transactionBoundDuringInitialize)
                .as("initializeTokenSegments must run on a thread that owns the transaction, but ran on [%s]",
                    initializeThread.get())
                .isTrue();
        assertThat(initializeThread)
                .as("""
                    initializeTokenSegments must be dispatched to the coordinator's executor, not run on the \
                    connector thread that completed the initial-token future""")
                .hasValue(COORDINATOR_THREAD_NAME);
    }
}
