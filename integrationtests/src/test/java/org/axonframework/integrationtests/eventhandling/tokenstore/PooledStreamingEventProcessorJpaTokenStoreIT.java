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

package org.axonframework.integrationtests.eventhandling.tokenstore;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.EntityManagerTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStoreConfiguration;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies a {@link PooledStreamingEventProcessor} on a JPA {@link JpaTokenStore} initializes its token segments when
 * the initial token resolves on a foreign thread, as the Axon Server connector's gRPC callbacks do.
 * <p>
 * Reproduced with {@link EntityManagerTransactionManager} and a thread-bound {@link EntityManagerProvider}, the
 * vendor-neutral stand-in for Spring's shared {@code EntityManager}.
 */
class PooledStreamingEventProcessorJpaTokenStoreIT {

    private static final String PROCESSOR_NAME = "fresh-processor";
    private static final String GRPC_THREAD_NAME = "axon-server-grpc-callback";

    private EntityManagerFactory entityManagerFactory;
    private ThreadBoundEntityManagerProvider entityManagerProvider;
    private ExecutorService grpcCallbackExecutor;
    private ScheduledExecutorService coordinatorExecutor;
    private ScheduledExecutorService workerExecutor;
    private PooledStreamingEventProcessor processor;

    @BeforeEach
    void setUp() {
        entityManagerFactory = Persistence.createEntityManagerFactory("tokenStore");

        // A thread-bound EntityManager: a persist requires a transaction bound to the current thread, exactly like
        // Spring's shared EntityManager, but without depending on Spring.
        entityManagerProvider = new ThreadBoundEntityManagerProvider(entityManagerFactory);
        UnitOfWorkFactory unitOfWorkFactory = new TransactionalUnitOfWorkFactory(
                new EntityManagerTransactionManager(entityManagerProvider),
                new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));

        JpaTokenStore tokenStore = new JpaTokenStore(new JpaTransactionalExecutorProvider(entityManagerFactory),
                                                     new JacksonConverter(),
                                                     JpaTokenStoreConfiguration.DEFAULT.nodeId("local"));

        grpcCallbackExecutor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, GRPC_THREAD_NAME));
        coordinatorExecutor = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "coordinator-executor"));
        workerExecutor = Executors.newScheduledThreadPool(2, runnable -> new Thread(runnable, "worker-executor"));

        var configuration = new PooledStreamingEventProcessorConfiguration(
                new EventProcessorConfiguration(PROCESSOR_NAME, null))
                .eventSource(new ForeignThreadInitialTokenEventSource(grpcCallbackExecutor))
                .unitOfWorkFactory(unitOfWorkFactory)
                .tokenStore(tokenStore)
                .coordinatorExecutor(coordinatorExecutor)
                .workerExecutor(workerExecutor)
                .initialSegmentCount(1);

        processor = new PooledStreamingEventProcessor(
                PROCESSOR_NAME,
                List.of(SimpleEventHandlingComponent.create(PROCESSOR_NAME)),
                configuration);
    }

    @AfterEach
    void tearDown() {
        if (processor != null) {
            try {
                processor.shutdown().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best-effort shutdown
            }
        }
        if (coordinatorExecutor != null) {
            coordinatorExecutor.shutdownNow();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
        if (grpcCallbackExecutor != null) {
            grpcCallbackExecutor.shutdownNow();
        }
        if (entityManagerProvider != null) {
            entityManagerProvider.closeAll();
        }
        if (entityManagerFactory != null && entityManagerFactory.isOpen()) {
            entityManagerFactory.close();
        }
    }

    @Test
    void startsAndInitializesJpaTokenStoreWhenInitialTokenResolvesOnForeignThread() {
        // when starting the processor on a fresh database; the initial token resolves on the gRPC callback thread
        CompletableFuture<Void> started = processor.start();

        // then start completes and the segment is persisted. Before the fix the persist ran on the gRPC thread,
        // whose EntityManager has no bound transaction, so start() completed exceptionally after exhausting retries.
        assertThatCode(() -> started.orTimeout(20, TimeUnit.SECONDS).join())
                .doesNotThrowAnyException();
        assertThat(persistedSegmentCount()).isEqualTo(1);
    }

    private long persistedSegmentCount() {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return entityManager.createQuery(
                                         "SELECT COUNT(t) FROM TokenEntry t WHERE t.processorName = :processorName",
                                         Long.class)
                                 .setParameter("processorName", PROCESSOR_NAME)
                                 .getSingleResult();
        } finally {
            entityManager.close();
        }
    }

    /**
     * Hands every thread its own {@link EntityManager}, so a transaction begun on one thread is invisible to others.
     */
    @NullMarked
    private static final class ThreadBoundEntityManagerProvider implements EntityManagerProvider {

        private final EntityManagerFactory entityManagerFactory;
        private final ThreadLocal<EntityManager> threadEntityManager = new ThreadLocal<>();
        private final Queue<EntityManager> created = new ConcurrentLinkedQueue<>();

        private ThreadBoundEntityManagerProvider(EntityManagerFactory entityManagerFactory) {
            this.entityManagerFactory = entityManagerFactory;
        }

        @Override
        public EntityManager getEntityManager() {
            EntityManager entityManager = threadEntityManager.get();
            if (entityManager == null || !entityManager.isOpen()) {
                entityManager = entityManagerFactory.createEntityManager();
                threadEntityManager.set(entityManager);
                created.add(entityManager);
            }
            return entityManager;
        }

        private void closeAll() {
            EntityManager entityManager;
            while ((entityManager = created.poll()) != null) {
                if (entityManager.isOpen()) {
                    entityManager.close();
                }
            }
        }
    }

    /**
     * A {@link StreamableEventSource} whose token futures complete on a foreign thread, mimicking the Axon Server
     * connector's gRPC callbacks. The stream itself is empty.
     */
    @NullMarked
    private record ForeignThreadInitialTokenEventSource(Executor grpcCallbackExecutor) implements StreamableEventSource {

        private CompletableFuture<TrackingToken> resolvedOnCallbackThread() {
            return CompletableFuture.supplyAsync(
                    () -> new GlobalSequenceTrackingToken(-1),
                    CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS, grpcCallbackExecutor));
        }

        @Override
        public CompletableFuture<TrackingToken> firstToken(@Nullable ProcessingContext context) {
            return resolvedOnCallbackThread();
        }

        @Override
        public CompletableFuture<TrackingToken> latestToken(@Nullable ProcessingContext context) {
            return resolvedOnCallbackThread();
        }

        @Override
        public CompletableFuture<TrackingToken> tokenAt(Instant at, @Nullable ProcessingContext context) {
            return resolvedOnCallbackThread();
        }

        @Override
        public MessageStream<EventMessage> open(StreamingCondition condition, @Nullable ProcessingContext context) {
            return MessageStream.empty();
        }
    }
}
