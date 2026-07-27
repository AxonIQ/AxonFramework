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

package org.axonframework.hunt.harness;

import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.hunt.fault.Buggify;
import org.axonframework.hunt.fault.FaultSite;
import org.axonframework.hunt.fault.PausePoint;
import org.axonframework.hunt.fault.StoreHook;
import org.axonframework.hunt.history.HistoryRecorder;
import org.axonframework.hunt.workload.Workload;
import org.axonframework.hunt.workload.WorkloadContext;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One wired, running Axon Framework, for the duration of one scenario run.
 * <p>
 * Everything in it is the framework's own: a real {@code SimpleCommandBus} over a real unit-of-work factory, a real
 * {@code StorageEngineBackedEventStore}, and a real {@code PooledStreamingEventProcessor} driving the workload's
 * projection. The single substitution is {@link ControllableEventStorageEngine}, which sits between the event store
 * and the backend so that the run can record what the store was actually asked to do and a fault can interfere with
 * it. No framework class is patched, subclassed or configured into a test-only mode.
 * <p>
 * Where the framework already offers an injection point, this class uses it rather than inventing a seam: the
 * streaming processor's coordinator and worker executors are the framework's own configuration settings, and the
 * timings come from the same settings a production application would set. The one seam the framework does <em>not</em>
 * offer is a per-run clock on the token-claim path, which reads a process-global static; nothing at this layer
 * depends on it, and no attempt is made here to pretend otherwise.
 * <p>
 * The world is also the {@link FaultSite}: it is the only way a fault reaches anything.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class HuntWorld implements FaultSite, AutoCloseable {

    private static final String PROCESSOR_NAME = "hunt-projection";
    private static final int REAL_THREAD_SEGMENTS = 4;
    private static final int REAL_THREAD_WORKERS = 4;
    private static final int PROJECTION_BATCH_SIZE = 64;

    private final HuntBackend backend;
    private final EventStorageEngine engine;
    private final ControllableEventStorageEngine store;
    private final EventStore eventStore;
    private final SimpleCommandBus commandBus;
    private final PausePoint pauses = new PausePoint();
    private final Buggify buggify;
    private final HuntTimescale timescale;
    private final ScheduledExecutorService coordinatorExecutor;
    private final ScheduledExecutorService workerExecutor;
    private final PooledStreamingEventProcessor processor;
    private final WorkloadContext context;
    private final List<String> participants;
    private final DeterminismMode determinism;

    private HuntWorld(HuntBackend backend,
                      Workload workload,
                      long seed,
                      int commands,
                      HistoryRecorder recorder,
                      Buggify buggify,
                      HuntTimescale timescale,
                      DeterminismMode mode,
                      Deadline deadline) {
        this.backend = backend;
        this.buggify = buggify;
        this.timescale = timescale;
        this.determinism = mode;
        this.participants = List.copyOf(workload.participants(seed, commands, mode));

        this.engine = backend.createEngine();
        this.store = new ControllableEventStorageEngine(engine, recorder, buggify);
        this.eventStore = new StorageEngineBackedEventStore(store, new SimpleEventBus(), workload.tagResolver());
        this.commandBus = new SimpleCommandBus(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));
        this.context = new WorkloadContext(this, seed, commands, recorder, deadline);

        EventHandlingComponent projection = workload.install(context);

        int workers = mode == DeterminismMode.SINGLE_THREADED ? 1 : REAL_THREAD_WORKERS;
        int segments = mode == DeterminismMode.SINGLE_THREADED ? 1 : REAL_THREAD_SEGMENTS;
        this.coordinatorExecutor = Executors.newScheduledThreadPool(1, named("hunt-coordinator"));
        this.workerExecutor = Executors.newScheduledThreadPool(workers, named("hunt-worker"));

        PooledStreamingEventProcessorConfiguration processorConfiguration =
                new PooledStreamingEventProcessorConfiguration(new EventProcessorConfiguration(PROCESSOR_NAME, null))
                        .eventSource(eventStore)
                        .tokenStore(new InMemoryTokenStore())
                        .coordinatorExecutor(coordinatorExecutor)
                        .workerExecutor(workerExecutor)
                        .initialSegmentCount(segments)
                        .initialToken(source -> source.firstToken(null))
                        .tokenClaimInterval(timescale.tokenClaimInterval().toMillis())
                        .claimExtensionThreshold(timescale.claimExtensionThreshold().toMillis())
                        .batchSize(PROJECTION_BATCH_SIZE);
        this.processor = new PooledStreamingEventProcessor(PROCESSOR_NAME, List.of(projection),
                                                           processorConfiguration);
    }

    /**
     * Wires and starts a world.
     *
     * @param backend  the store the run is driven against
     * @param workload the load to install; its command handlers and projection are registered here
     * @param seed     the seed fixing the workload's shape
     * @param commands how many commands the run will issue
     * @param recorder the recorder every operation is written to
     * @param buggify  the scheduling-bias points, inert unless a scenario arms them
     * @param timescale the timings the processor is configured with
     * @param mode     how much of the run's scheduling to pin down
     * @param deadline the run's wall-clock stop
     * @return a started world
     */
    public static HuntWorld start(HuntBackend backend,
                                  Workload workload,
                                  long seed,
                                  int commands,
                                  HistoryRecorder recorder,
                                  Buggify buggify,
                                  HuntTimescale timescale,
                                  DeterminismMode mode,
                                  Deadline deadline) {
        Objects.requireNonNull(backend, "The backend cannot be null.");
        Objects.requireNonNull(workload, "The workload cannot be null.");
        Objects.requireNonNull(recorder, "The recorder cannot be null.");
        Objects.requireNonNull(buggify, "The buggify cannot be null.");
        Objects.requireNonNull(timescale, "The timescale cannot be null.");
        Objects.requireNonNull(mode, "The mode cannot be null.");
        Objects.requireNonNull(deadline, "The deadline cannot be null.");
        HuntWorld world = new HuntWorld(backend, workload, seed, commands, recorder, buggify, timescale, mode,
                                        deadline);
        world.processor.start().orTimeout(30, TimeUnit.SECONDS).join();
        return world;
    }

    /**
     * Returns the run's command bus.
     *
     * @return the command bus
     */
    public SimpleCommandBus commandBus() {
        return commandBus;
    }

    /**
     * Returns the run's event store.
     *
     * @return the event store
     */
    public EventStore eventStore() {
        return eventStore;
    }

    /**
     * Returns the fault-injecting wrapper sitting between the event store and the backend.
     *
     * @return the store wrapper
     */
    public ControllableEventStorageEngine store() {
        return store;
    }

    /**
     * Returns the timings the run was configured with.
     *
     * @return the timescale arm
     */
    public HuntTimescale timescale() {
        return timescale;
    }

    /**
     * Returns how much of this run's scheduling is pinned down.
     *
     * @return the determinism mode
     */
    public DeterminismMode determinism() {
        return determinism;
    }

    /**
     * Returns the context the workload was installed with.
     *
     * @return the workload context
     */
    public WorkloadContext context() {
        return context;
    }

    @Override
    public void installStoreHook(StoreHook hook) {
        store.installHook(hook);
    }

    @Override
    public void removeStoreHook(StoreHook hook) {
        store.removeHook(hook);
    }

    @Override
    public PausePoint pauses() {
        return pauses;
    }

    @Override
    public Buggify buggify() {
        return buggify;
    }

    @Override
    public List<String> participants() {
        return participants;
    }

    /**
     * Stops the processor and releases every thread and every store the run held.
     */
    @Override
    public void close() {
        pauses.resumeAll();
        try {
            processor.shutdown().orTimeout(30, TimeUnit.SECONDS).join();
        } catch (RuntimeException e) {
            // A processor that will not stop is a finding about the processor, not a reason to leak the threads
            // below; the failure is visible in the run's own liveness accounting.
        }
        shutdown(coordinatorExecutor);
        shutdown(workerExecutor);
        backend.release(engine);
    }

    private static void shutdown(ScheduledExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Returns how long the run should keep waiting for the read side to catch up before it gives up and reports the
     * run as undecided.
     *
     * @return the quiescence budget from the run's timescale
     */
    public Duration quiescenceBudget() {
        return timescale.quiescence();
    }
}
