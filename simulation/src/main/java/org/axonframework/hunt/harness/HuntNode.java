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

import org.axonframework.hunt.fault.PausePoint;
import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecorder;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.DelegatingEventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * One framework instance in a run: its own processor, its own threads, its own node identity, everybody else's store.
 * <p>
 * A node is what a deployed application process is, reduced to the part that competes with its peers. It holds a
 * {@link PooledStreamingEventProcessor} of its own, a coordinator and a worker thread of its own, and its own view of
 * the run's token store carrying its own node identity; it shares the event store and the read model with every other
 * node, because that is what a cluster of one application over one database looks like.
 * <p>
 * <b>Crashing is the operation worth being careful about.</b> Shutting a processor down cleanly releases its claims,
 * which is the one thing a crash never does: a process that dies leaves its rows owned, with a timestamp that stops
 * advancing, and every guarantee about stealing exists precisely for that state. So {@link #crash()} does not shut the
 * processor down. It drops the node's threads and walks away, leaving the claims exactly as the dead process left
 * them, and it is the only faithful way to reach the state the claim algebra was written for.
 * <p>
 * A node also carries a pause seam. The workload's projection is wrapped so that every event this node handles passes
 * a checkpoint named after the node, which is how a stall reaches a worker without suspending a thread from outside --
 * suspending one that happens to hold a framework lock would wedge the run rather than test it.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class HuntNode implements AutoCloseable {

    private final String nodeId;
    private final String processorName;
    private final EventHandlingComponent projection;
    private final Supplier<PooledStreamingEventProcessorConfiguration> configurations;
    private final TokenStore tokenStore;
    private final HistoryRecorder.ProcessRecorder recorder;
    private final int workers;

    private final AtomicReference<Running> running = new AtomicReference<>();

    HuntNode(String nodeId,
             String processorName,
             EventHandlingComponent projection,
             TokenStore tokenStore,
             PausePoint pauses,
             HistoryRecorder recorder,
             Supplier<PooledStreamingEventProcessorConfiguration> configurations,
             int workers) {
        this.nodeId = Objects.requireNonNull(nodeId, "The nodeId cannot be null.");
        this.processorName = Objects.requireNonNull(processorName, "The processorName cannot be null.");
        this.recorder = recorder.forProcess(nodeId, nodeId);
        this.projection = new PausingComponent(projection, pauses, nodeId);
        this.workers = workers;
        this.configurations = Objects.requireNonNull(configurations, "The configurations cannot be null.");
        // Wrapped once and reused across restarts, so a restarted node keeps one identity in the recorded history.
        this.tokenStore = new RecordingTokenStore(tokenStore, this.recorder);
    }

    /**
     * Returns the node's identity, which is the owner its claims are recorded under.
     *
     * @return the node identifier
     */
    public String nodeId() {
        return nodeId;
    }

    /**
     * Indicates whether the node is currently processing.
     *
     * @return {@code true} between a start or restart and the next crash or close
     */
    public boolean isRunning() {
        return running.get() != null;
    }

    /**
     * Asks the store for its identifier, creating it if the store has none yet.
     * <p>
     * Called once, before a cluster's nodes are released, and only to get the creation of that row out of the way.
     * It is the first thing a processor does when it starts, and on a shared JDBC token store several processors
     * doing it at once end with all but one failing to start (finding F-9). Left to happen naturally, that failure
     * serialises the boot: one node comes up, creates the segments while the others are retrying, and the segment
     * race a concurrent bootstrap exists to observe never happens. Doing it up front is the second deployment, where
     * the row is already there, and it is the only way the layer underneath is reachable at all.
     *
     * @return a future completing with the store's identifier
     */
    public CompletableFuture<String> resolveStorageIdentifier() {
        return tokenStore.retrieveStorageIdentifier(null);
    }

    /**
     * Asks the store which segments it holds for this run's processor.
     * <p>
     * Listing segments carries no claim decision, so it goes through the recording wrapper untouched and does not
     * appear in the run's history as an operation.
     *
     * @return the segments the store holds
     */
    public CompletableFuture<List<org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment>>
    segments() {
        return tokenStore.fetchSegments(processorName, null);
    }

    /**
     * Starts the node's processor and returns without waiting for it.
     * <p>
     * The future is deliberately not joined here. A run whose nodes boot one after another never produces the race
     * that a first deployment produces, and that race is the whole point of starting several at once; the caller
     * joins all of them together.
     *
     * @return a future completing when this node's processor has started
     */
    public CompletableFuture<Void> start() {
        return startInternal("started");
    }

    /**
     * Starts the node, records a startup failure, and gives it exactly one more try.
     * <p>
     * <b>Both halves of this exist because of what a simultaneous boot does to a shared JDBC token store.</b> Several
     * processors starting at the same instant each try to create the store's identifier row; one wins and the rest
     * get a primary-key violation that the store turns into a startup failure rather than re-reading the row, so all
     * but one instance fails to come up. Recording that is the point -- it is finding F-9, and the history is where
     * the evidence for it lives. Retrying once afterwards is what a supervised deployment does with a process that
     * died on startup, and it is what lets the rest of the cluster layer be exercised at all instead of every
     * multi-node scenario stopping at the same defect.
     * <p>
     * A node that fails twice is left down. The run continues without it, which is again what a real deployment does.
     *
     * @return a future completing when this node has either started or been recorded as down
     */
    public CompletableFuture<Void> startOrRecordFailure() {
        return attempt("started").thenCompose(started -> started
                ? CompletableFuture.completedFuture(null)
                : attempt("started-after-retry").thenApply(ignored -> null));
    }

    private CompletableFuture<Boolean> attempt(String action) {
        return startInternal(action).handle((ignored, failure) -> {
            if (failure == null) {
                return true;
            }
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            running.set(null);
            recorder.info(HistoryOps.NODE, nodeId,
                          Map.of(HistoryOps.ACTION, "start-failed", "error", cause.getClass().getName()));
            return false;
        });
    }

    /**
     * Drops the node without releasing anything it holds.
     * <p>
     * Its threads are cancelled and its processor is abandoned mid-flight, so its claims stay in the store with the
     * timestamp they last carried and can only be recovered by another node stealing them once they expire.
     */
    public void crash() {
        Running current = running.getAndSet(null);
        if (current == null) {
            return;
        }
        stopDead(current.coordinatorExecutor());
        stopDead(current.workerExecutor());
        recorder.info(HistoryOps.NODE, nodeId, Map.of(HistoryOps.ACTION, "crashed"));
    }

    /**
     * Stops an executor the way a dead process stops one: work handed to it afterwards vanishes.
     * <p>
     * The default rejection policy throws, and in this harness the throw lands somewhere it never could in
     * production. Every node shares one in-heap event store, and a commit notifies the store's open streams inline on
     * the committing thread; if one of those streams belongs to a node whose executor has just been shut down, the
     * rejection is raised inside an unrelated writer's commit and rolls that writer's transaction back. A real
     * crashed process is simply not there to be notified. Discarding is what models that, and without it the fault
     * manufactures failures in code paths it never touched.
     */
    private static void stopDead(ScheduledThreadPoolExecutor executor) {
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.shutdownNow();
    }

    /**
     * Brings a crashed node back under the same identity.
     * <p>
     * Same node identity, new threads, new processor: which is what a restarted application process is, and why it can
     * re-claim what it owned before without waiting for anything to expire.
     *
     * @return a future completing when this node's processor has started again
     */
    public CompletableFuture<Void> restart() {
        if (isRunning()) {
            return CompletableFuture.completedFuture(null);
        }
        return startInternal("restarted").handle((ignored, failure) -> {
            if (failure != null) {
                running.set(null);
                recorder.info(HistoryOps.NODE, nodeId,
                              Map.of(HistoryOps.ACTION, "start-failed",
                                     "error", failure.getClass().getName()));
            }
            return null;
        });
    }

    /**
     * Stops the node the way an orderly shutdown does, releasing what it holds.
     * <p>
     * A crashed node is left alone: asking a processor whose threads are gone to shut down cleanly waits for workers
     * that will never run again.
     */
    @Override
    public void close() {
        Running current = running.getAndSet(null);
        if (current == null) {
            return;
        }
        try {
            current.processor().shutdown().orTimeout(30, TimeUnit.SECONDS).join();
        } catch (RuntimeException e) {
            // A processor that will not stop is a finding about the processor, not a reason to leak the threads
            // below; the failure is visible in the run's own liveness accounting.
        }
        shutdown(current.coordinatorExecutor());
        shutdown(current.workerExecutor());
    }

    private CompletableFuture<Void> startInternal(String action) {
        ScheduledThreadPoolExecutor coordinatorExecutor =
                new ScheduledThreadPoolExecutor(1, named(nodeId + "-coordinator"));
        ScheduledThreadPoolExecutor workerExecutor =
                new ScheduledThreadPoolExecutor(workers, named(nodeId + "-worker"));
        PooledStreamingEventProcessor processor =
                new PooledStreamingEventProcessor(processorName, List.of(projection),
                                                  configurations.get()
                                                                .tokenStore(tokenStore)
                                                                .coordinatorExecutor(coordinatorExecutor)
                                                                .workerExecutor(workerExecutor));
        running.set(new Running(processor, coordinatorExecutor, workerExecutor));
        recorder.info(HistoryOps.NODE, nodeId, Map.of(HistoryOps.ACTION, action));
        return processor.start();
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

    private record Running(PooledStreamingEventProcessor processor,
                           ScheduledThreadPoolExecutor coordinatorExecutor,
                           ScheduledThreadPoolExecutor workerExecutor) {

    }

    /**
     * Holds this node's handling thread at a checkpoint while the rest of the cluster keeps running.
     * <p>
     * The stall happens inside the handler, which is where a garbage-collection pause or a frozen virtual machine
     * would land a real process: mid-batch, holding a claim it can no longer extend, about to wake into a cluster that
     * has moved on.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    private static final class PausingComponent extends DelegatingEventHandlingComponent {

        private final PausePoint pauses;
        private final String node;

        private PausingComponent(EventHandlingComponent delegate, PausePoint pauses, String node) {
            super(delegate);
            this.pauses = pauses;
            this.node = node;
        }

        @Override
        public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
            try {
                Duration ignored = pauses.checkpoint(node);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.handle(event, context);
        }
    }
}
