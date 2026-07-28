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
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * <b>One world, one or many nodes.</b> A node is a framework instance with its own processor, its own threads and its
 * own token-store identity; every node shares the one event store, the one token store and the one read model, which
 * is what a cluster of one application over one database is. A single-node run is the same code with the loop running
 * once, so nothing about the layer below has to know which it is. The nodes are started together rather than one
 * after another, because a cluster booted in sequence never produces the race a first deployment produces.
 * <p>
 * The world is also the {@link FaultSite}: it is the only way a fault reaches anything.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class HuntWorld implements FaultSite, AutoCloseable {

    /**
     * How many events one batch of the run's projection may hold.
     * <p>
     * Public because it bounds what a claim handover may legitimately repeat: a batch's handler effects and its token
     * progress are persisted in one transaction, so the events a segment redelivers after changing hands are the events
     * of at most one batch. An oracle checking that needs the number, so the number travels in the history header.
     */
    public static final int PROJECTION_BATCH_SIZE = 64;

    /**
     * The components a processing context of this run can resolve.
     * <p>
     * Only one is ever asked for, and finding out which cost an afternoon: rewinding a processor's tokens converts the
     * reset context through a {@code GeneralConverter} taken from the processing context, and it does so whether or not
     * a reset context was given, so a reset with nothing to convert still needs one. A plain-Java processor wired over
     * an application context that provides no components therefore cannot be reset at all -- the call fails with an
     * {@code UnsupportedOperationException} out of the empty context rather than with anything about resets. Supplying
     * the converter here is harness wiring, not a workaround for a defect, but the fact that a null reset context
     * demands a converter is recorded as a finding.
     */
    private static final org.axonframework.messaging.core.ApplicationContext HARNESS_COMPONENTS =
            new HarnessComponents();

    private static final String PROCESSOR_NAME = "hunt-projection";
    private static final int REAL_THREAD_WORKERS = 4;
    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);

    private final HuntBackend backend;
    private final EventStorageEngine engine;
    private final ControllableEventStorageEngine store;
    private final EventStore eventStore;
    private final SimpleCommandBus commandBus;
    private final org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory unitOfWorkFactory;
    private final PausePoint pauses = new PausePoint();
    private final Buggify buggify;
    private final HuntTimescale timescale;
    private final TokenStores tokenStores;
    private final List<HuntNode> nodes;
    private final WorkloadContext context;
    private final List<String> participants;
    private final DeterminismMode determinism;
    private final @org.jspecify.annotations.Nullable String skewedNodeId;

    private HuntWorld(HuntBackend backend,
                      Workload workload,
                      long seed,
                      int commands,
                      Topology topology,
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
        this.unitOfWorkFactory = unitOfWorkFactory(backend, engine);
        this.commandBus = new SimpleCommandBus(unitOfWorkFactory);
        this.context = new WorkloadContext(this, seed, commands, recorder, deadline);

        EventHandlingComponent projection = workload.install(context);

        int nodeCount = topology.nodes();
        int workers = mode == DeterminismMode.SINGLE_THREADED ? 1 : REAL_THREAD_WORKERS;
        int segments = mode == DeterminismMode.SINGLE_THREADED ? 1 : topology.segments();
        this.tokenStores = backend.createTokenStores(seed + "-" + System.identityHashCode(this),
                                                     timescale.tokenStoreClaimTimeout());

        // Without a cap the first node to reach the store claims every segment and the rest of the cluster sits idle:
        // the shipped maximum is Short.MAX_VALUE, so nothing pushes work outwards. A cluster whose segments all live
        // on one node is a single-node run with extra threads, and no ownership question arises in it. Sharing the
        // segments out evenly is the configuration a real multi-instance deployment uses and the only one in which
        // ownership means anything.
        int maxSegmentsPerNode = nodeCount == 1
                ? segments
                : topology.segmentsPerNode() == null
                        ? Math.max(1, segments / nodeCount)
                        : topology.segmentsPerNode();

        // The skewed node is the last one, never the first: the first is the one that resolves the store's identifier
        // before the boot barrier is released, and a node that considers every claim expired is the worst possible
        // choice for that job.
        String skewedNode = timescale.emulatedClockSkew().isZero() ? null : "node-" + (nodeCount - 1);
        this.skewedNodeId = skewedNode;

        List<HuntNode> built = new ArrayList<>(nodeCount);
        for (int index = 0; index < nodeCount; index++) {
            String nodeId = "node-" + index;
            Duration skew = nodeId.equals(skewedNode) ? timescale.emulatedClockSkew() : Duration.ZERO;
            built.add(new HuntNode(nodeId,
                                   PROCESSOR_NAME,
                                   projection,
                                   tokenStores.forNode(nodeId, skew),
                                   pauses,
                                   recorder,
                                   () -> configuration(segments, maxSegmentsPerNode),
                                   workers));
        }
        this.nodes = List.copyOf(built);
    }

    /**
     * The shape of one run's cluster: how many framework instances, how many segments, and how many of those segments
     * one instance may hold at once.
     *
     * @param nodes          how many framework instances share the run's store and token store
     * @param segments       how many segments the processors divide the stream into
     * @param segmentsPerNode how many segments one node may hold, or {@code null} to share them out evenly
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record Topology(int nodes, int segments, @org.jspecify.annotations.Nullable Integer segmentsPerNode) {

        /**
         * Compact constructor rejecting a cluster with nothing in it.
         */
        public Topology {
            if (nodes < 1 || segments < 1) {
                throw new IllegalArgumentException(
                        "A topology needs at least one node and one segment, but was " + nodes + " node(s) over "
                                + segments + " segment(s).");
            }
        }

        /**
         * Returns a single-node topology over the default segment count.
         *
         * @return the topology one node runs under
         */
        public static Topology single() {
            return new Topology(1, 4, null);
        }
    }

    /**
     * Returns the factory every unit of work of the run is opened by.
     * <p>
     * A backend that speaks to a database supplies a transaction manager, and wrapping the factory in it is what puts
     * that transaction on the processing context -- which is where a persistent storage engine and a persistent token
     * store both look for the executor to use, and which is what makes an append commit in the framework's commit phase
     * rather than the moment the engine is handed the events. An in-heap backend supplies none and gets the plain
     * factory.
     */
    private static org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory unitOfWorkFactory(
            HuntBackend backend, EventStorageEngine engine) {
        SimpleUnitOfWorkFactory plain = new SimpleUnitOfWorkFactory(HARNESS_COMPONENTS);
        var transactionManager = backend.transactionManager(engine);
        return transactionManager == null
                ? plain
                : new org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory(transactionManager,
                                                                                                plain);
    }

    private PooledStreamingEventProcessorConfiguration configuration(int segments, int maxSegmentsPerNode) {
        return new PooledStreamingEventProcessorConfiguration(new EventProcessorConfiguration(PROCESSOR_NAME, null))
                // The processor opens its own units of work, and rewinding its tokens resolves a general-purpose
                // converter out of one of them. The framework's default here is an application context that provides
                // nothing, so without this a reset fails with an UnsupportedOperationException that says nothing about
                // resets at all.
                .unitOfWorkFactory(unitOfWorkFactory)
                .eventSource(eventStore)
                .initialSegmentCount(segments)
                .maxClaimedSegments(maxSegmentsPerNode)
                .initialToken(source -> source.firstToken(null))
                .tokenClaimInterval(timescale.tokenClaimInterval().toMillis())
                .claimExtensionThreshold(timescale.claimExtensionThreshold().toMillis())
                .batchSize(PROJECTION_BATCH_SIZE);
    }

    /**
     * Wires and starts a single-node world.
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
        return start(backend, workload, seed, commands, Topology.single(), recorder, buggify, timescale, mode,
                     deadline);
    }

    /**
     * Wires a world of the given shape and starts every node at once.
     *
     * @param backend   the store the run is driven against
     * @param workload  the load to install; its command handlers and projection are registered here
     * @param seed      the seed fixing the workload's shape
     * @param commands  how many commands the run will issue
     * @param topology  how many nodes and segments the run has, and how many segments one node may hold
     * @param recorder  the recorder every operation is written to
     * @param buggify   the scheduling-bias points, inert unless a scenario arms them
     * @param timescale the timings the processors are configured with
     * @param mode      how much of the run's scheduling to pin down
     * @param deadline  the run's wall-clock stop
     * @return a started world
     */
    public static HuntWorld start(HuntBackend backend,
                                  Workload workload,
                                  long seed,
                                  int commands,
                                  Topology topology,
                                  HistoryRecorder recorder,
                                  Buggify buggify,
                                  HuntTimescale timescale,
                                  DeterminismMode mode,
                                  Deadline deadline) {
        Objects.requireNonNull(backend, "The backend cannot be null.");
        Objects.requireNonNull(topology, "The topology cannot be null.");
        Objects.requireNonNull(workload, "The workload cannot be null.");
        Objects.requireNonNull(recorder, "The recorder cannot be null.");
        Objects.requireNonNull(buggify, "The buggify cannot be null.");
        Objects.requireNonNull(timescale, "The timescale cannot be null.");
        Objects.requireNonNull(mode, "The mode cannot be null.");
        Objects.requireNonNull(deadline, "The deadline cannot be null.");
        HuntWorld world = new HuntWorld(backend, workload, seed, commands, topology, recorder, buggify, timescale,
                                        mode, deadline);
        world.bootTogether();
        return world;
    }

    /**
     * Starts every node from its own thread, all released at the same instant.
     * <p>
     * Calling {@code start()} on each node in turn looks concurrent and is not: the first node's coordinator has
     * created and claimed every segment before the loop reaches the second, and the cluster boots in single file.
     * That was measured on this harness -- with a sequential loop, exactly one of four nodes ever attempted to create
     * the segments, so the arm built to observe a bootstrap race observed no race at all. Releasing the calls from a
     * barrier is what makes the first deployment a first deployment.
     */
    private void bootTogether() {
        if (nodes.size() == 1) {
            nodes.getFirst().start().orTimeout(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
            return;
        }
        nodes.getFirst().resolveStorageIdentifier().orTimeout(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(nodes.size());
        List<CompletableFuture<Void>> started = new ArrayList<>(nodes.size());
        for (HuntNode node : nodes) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            started.add(future);
            Thread starter = new Thread(() -> {
                ready.countDown();
                try {
                    release.await();
                    // A node that cannot start is recorded and left down, not thrown out of the run. A deployment
                    // where some instances failed to come up is a real state of the world and the interesting one;
                    // aborting here would replace a run the oracles can judge with a stack trace nobody can.
                    node.startOrRecordFailure().orTimeout(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    // Already recorded by the node.
                }
                future.complete(null);
            }, node.nodeId() + "-boot");
            starter.setDaemon(true);
            starter.start();
        }
        try {
            if (!ready.await(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Not every node reached the boot barrier within "
                                                        + START_TIMEOUT + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the boot barrier.", e);
        }
        release.countDown();
        CompletableFuture.allOf(started.toArray(CompletableFuture[]::new))
                         .orTimeout(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                         .join();
        if (nodes.stream().noneMatch(HuntNode::isRunning)) {
            throw new IllegalStateException("No node of the cluster started, so there is nothing to run.");
        }
    }

    /**
     * Returns the run's nodes, in the order they were created.
     *
     * @return the nodes; a single-node run returns one
     */
    public List<HuntNode> nodes() {
        return nodes;
    }

    /**
     * Returns the segments the run's token store holds, as the store itself reports them.
     * <p>
     * This is the authoritative answer to what a bootstrap produced, and the only one: the history says how many
     * initialisations were attempted and which of them the store accepted, which is not the same as how many rows
     * the store ended up with. Asking is what turns "exactly once" from an inference into a measurement.
     *
     * @return the segment identifiers, ascending
     */
    public List<Integer> knownSegments() {
        return nodes.getFirst()
                    .segments()
                    .orTimeout(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join()
                    .stream()
                    .map(org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment::getSegmentId)
                    .sorted()
                    .toList();
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

    @Override
    public List<String> nodeNames() {
        return nodes.stream().map(HuntNode::nodeId).toList();
    }

    /**
     * Returns the node whose store view treats a claim as expired early, emulating a clock that runs ahead.
     *
     * @return the skewed node's identity, or {@code null} when the run declared no skew
     */
    public @org.jspecify.annotations.Nullable String skewedNodeId() {
        return skewedNodeId;
    }

    @Override
    public void crashNode(String nodeId) {
        nodes.stream().filter(node -> node.nodeId().equals(nodeId)).forEach(HuntNode::crash);
    }

    @Override
    public void stopNode(String nodeId) {
        nodes.stream().filter(node -> node.nodeId().equals(nodeId)).forEach(HuntNode::close);
    }

    @Override
    public void resetNode(String nodeId) {
        nodes.stream()
             .filter(node -> node.nodeId().equals(nodeId))
             .forEach(node -> node.resetAndRestart()
                                  .orTimeout(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                                  .join());
    }

    @Override
    public @org.jspecify.annotations.Nullable Throwable resetRunningNode(String nodeId) {
        return nodes.stream()
                    .filter(node -> node.nodeId().equals(nodeId))
                    .findFirst()
                    .map(HuntNode::attemptResetWhileRunning)
                    .orElse(null);
    }

    @Override
    public List<Integer> claimedSegments(String nodeId) {
        return nodes.stream()
                    .filter(node -> node.nodeId().equals(nodeId))
                    .findFirst()
                    .map(HuntNode::claimedSegments)
                    .orElse(List.of());
    }

    @Override
    public boolean splitSegment(String nodeId, int segmentId) {
        return nodes.stream()
                    .filter(node -> node.nodeId().equals(nodeId))
                    .findFirst()
                    .map(node -> node.splitSegment(segmentId))
                    .orElse(false);
    }

    @Override
    public boolean mergeSegment(String nodeId, int segmentId) {
        return nodes.stream()
                    .filter(node -> node.nodeId().equals(nodeId))
                    .findFirst()
                    .map(node -> node.mergeSegment(segmentId))
                    .orElse(false);
    }

    @Override
    public void restartNode(String nodeId) {
        nodes.stream()
             .filter(node -> node.nodeId().equals(nodeId))
             .forEach(node -> node.restart().orTimeout(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join());
    }

    /**
     * Stops every node and releases every thread and every store the run held.
     */
    @Override
    public void close() {
        pauses.resumeAll();
        nodes.forEach(HuntNode::close);
        tokenStores.close();
        backend.release(engine);
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

    /**
     * The smallest application context a run can get away with: one general-purpose converter and nothing else.
     * <p>
     * A processing context resolves components through the application context the unit-of-work factory was built with.
     * The framework's own empty context throws for every request, which is right for a test that resolves nothing and
     * wrong for a run that rewinds a processor.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    private static final class HarnessComponents implements org.axonframework.messaging.core.ApplicationContext {

        private final org.axonframework.conversion.GeneralConverter converter =
                new org.axonframework.conversion.DelegatingGeneralConverter(
                        new org.axonframework.conversion.jackson.JacksonConverter());

        @Override
        @SuppressWarnings("unchecked")
        public <C> C component(Class<C> type, @org.jspecify.annotations.Nullable String name) {
            if (type.isInstance(converter)) {
                return (C) converter;
            }
            throw new UnsupportedOperationException(
                    "The hunt harness provides no component of type [" + type.getName() + "].");
        }
    }
}
