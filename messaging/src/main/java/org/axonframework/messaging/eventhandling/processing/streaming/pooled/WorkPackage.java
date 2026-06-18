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

import org.axonframework.common.Assert;
import org.axonframework.common.ClockUtils;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.CheckpointTrigger;
import org.axonframework.messaging.eventhandling.processing.streaming.checkpoint.Checkpointing;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.TrackerStatus;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.WrappedToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.axonframework.common.FutureUtils.emptyCompletedFuture;

/**
 * Defines the process of handling {@link EventMessage}s for a specific {@link Segment}. This entails validating if the
 * event can be handled through a {@link EventFilter} and after that processing a collection of events in the
 * {@link BatchProcessor}.
 * <p>
 * Events are received through the {@link #scheduleEvent(MessageStream.Entry)} operation, delegated by a
 * {@link Coordinator}. Receiving event(s) means this {@link WorkPackage} will be scheduled to process these events
 * through an {@link ExecutorService}. As there are local threads and outside threads invoking methods on the
 * {@code WorkPackage}, several methods have threading notes describing what can invoke them safely.
 * <p>
 * Since the {@code WorkPackage} is in charge of a {@code Segment}, it maintains the claim on the matching
 * {@link TrackingToken}. In absence of new events, it will also
 * {@link TokenStore#extendClaim(String, int, ProcessingContext)} on the {@code TrackingToken}.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @see PooledStreamingEventProcessor
 * @see Coordinator
 * @since 4.5
 */
class WorkPackage {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    static final int BUFFER_SIZE = 1024;

    private final String name;
    private final TokenStore tokenStore;
    private final UnitOfWorkFactory unitOfWorkFactory;
    private final ExecutorService executorService;
    private final EventFilter eventFilter;
    private final BatchProcessor batchProcessor;
    private final Segment segment;
    private final int batchSize;
    private final long claimExtensionThreshold;
    private final Consumer<UnaryOperator<TrackerStatus>> segmentStatusUpdater;
    private final Supplier<ProcessingContext> schedulingProcessingContextProvider;
    private final boolean autoCheckpointing;
    @Deprecated(forRemoval = true, since = "5.2.0")
    private final Clock clock;
    private final List<Checkpointing> checkpointingParticipants;
    /**
     * The pending segment-scoped checkpoint request: the position any component declared safe, forward-merged via
     * {@link TrackingToken#upperBound(TrackingToken)}. Set from arbitrary threads through the {@link CheckpointTrigger}
     * (and by this package itself every batch in auto mode); consumed on the worker thread.
     */
    private final AtomicReference<@Nullable TrackingToken> requestedCheckpoint = new AtomicReference<>();

    private TrackingToken lastDeliveredToken; // For use only by event delivery threads, like Coordinator
    /**
     * Guards the {@link CheckpointTrigger} once the segment is released. Set during {@link #abort(Throwable)}; once
     * set, a checkpoint request is a no-op (a late async-write acknowledgement has no safe point left to record).
     */
    private final AtomicBoolean checkpointsReleased = new AtomicBoolean();
    private @Nullable TrackingToken lastStoredToken;
    private final AtomicLong nextClaimExtension;
    private final AtomicBoolean processingEvents;

    private final Queue<ProcessingEntry> processingQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicReference<@Nullable CompletableFuture<Throwable>> abortFlag = new AtomicReference<>();
    private final AtomicReference<@Nullable Throwable> abortException = new AtomicReference<>();
    private @Nullable Runnable batchProcessedCallback;
    private volatile @Nullable TrackingToken lastConsumedToken;
    private final CheckpointTrigger checkpointTrigger = new CheckpointTrigger() {
        @Override
        public void requestCheckpoint() {
            onCheckpointRequested(lastConsumedToken);
        }

        @Override
        public void requestCheckpoint(TrackingToken token) {
            onCheckpointRequested(token);
        }
    };

    /**
     * Instantiate a Builder to be able to create a {@code WorkPackage}. This builder <b>does not</b> validate the
     * fields. Hence, any fields provided should be validated by the user of the {@link WorkPackage.Builder}.
     *
     * @return a Builder to be able to create a {@code WorkPackage}
     */
    protected static Builder builder() {
        return new Builder();
    }

    private WorkPackage(Builder builder) {
        this.name = builder.name;
        this.tokenStore = builder.tokenStore;
        this.unitOfWorkFactory = builder.unitOfWorkFactory;
        this.executorService = builder.executorService;
        this.eventFilter = builder.eventFilter;
        this.batchProcessor = builder.batchProcessor;
        this.segment = builder.segment;
        this.lastDeliveredToken = builder.initialToken;
        this.batchSize = builder.batchSize;
        this.claimExtensionThreshold = builder.claimExtensionThreshold;
        this.segmentStatusUpdater = builder.segmentStatusUpdater;
        this.clock = builder.clock;
        this.autoCheckpointing = builder.autoCheckpointing;
        this.checkpointingParticipants = List.copyOf(builder.checkpointingParticipants);
        this.lastConsumedToken = builder.initialToken;
        this.nextClaimExtension = new AtomicLong(now() + claimExtensionThreshold);
        this.processingEvents = new AtomicBoolean(false);
        this.schedulingProcessingContextProvider = builder.schedulingProcessingContextProvider;
    }

    private long now() {
        return clock.instant().toEpochMilli();
    }

    /**
     * Schedule a collection of {@link MessageStream.Entry MessageStream.Entries} for processing by this work package.
     * <p>
     * Only use this method if the {@link TrackingToken TrackingTokens} of every event are equal, as those events should
     * be handled within a single transaction. This scenario presents itself whenever an event is upcasted into
     * <em>several instances</em>. When tokens differ between events please use
     * {@link #scheduleEvent(MessageStream.Entry)}.
     * <p>
     * Will disregard the given {@code eventEntries} if their {@code TrackingTokens} are covered by the previously
     * scheduled event.
     * <p>
     * <b>Threading note:</b> This method is and should only to be called by the {@link Coordinator} thread of a {@link
     * PooledStreamingEventProcessor}.
     *
     * @param eventEntries The event entries to schedule for work in this work package.
     * @return {@code True} if this {@code WorkPackage} scheduled one of the events for execution, otherwise
     * {@code false}.
     */
    public boolean scheduleEvents(List<MessageStream.Entry<? extends EventMessage>> eventEntries) {
        if (eventEntries.isEmpty()) {
            // cannot schedule an empty events list
            return false;
        }
        assertEqualTokens(eventEntries);

        if (eventEntries.stream().allMatch(this::shouldNotSchedule)) {
            if (logger.isTraceEnabled()) {
                eventEntries.forEach(eventEntry -> {
                    TrackingToken eventToken = TrackingToken.fromContext(eventEntry).orElse(null);
                    logger.trace(
                            "Ignoring event [{}] with position [{}] for work package [{}]. "
                                    + "The last token [{}] covers event's token [{}].",
                            eventEntry.message().identifier(),
                            eventToken != null ? eventToken.position().orElse(-1) : -1,
                            segment.getSegmentId(),
                            lastDeliveredToken,
                            eventToken
                    );
                });
            }
            return false;
        }

        BatchProcessingEntry batchProcessingEntry = new BatchProcessingEntry();
        boolean canHandleAny = eventEntries.stream()
                                           .map(eventEntry -> {
                                               boolean canHandle = canHandleMessage(eventEntry);
                                               batchProcessingEntry.add(new DefaultProcessingEntry(eventEntry,
                                                                                                   canHandle));
                                               return canHandle;
                                           })
                                           .reduce(Boolean::logicalOr)
                                           .orElse(false);

        processingQueue.add(batchProcessingEntry);
        lastDeliveredToken = batchProcessingEntry.trackingToken();
        // the worker must always be scheduled to ensure claims are extended
        scheduleWorker();

        return canHandleAny;
    }

    private void assertEqualTokens(List<MessageStream.Entry<? extends EventMessage>> eventEntries) {
        TrackingToken expectedToken = TrackingToken.fromContext(eventEntries.getFirst()).orElse(null);
        Assert.isTrue(
                eventEntries.stream()
                            .map(entry -> TrackingToken.fromContext(entry).orElse(null))
                            .allMatch(token -> Objects.equals(expectedToken, token)),
                () -> "All tokens should match when scheduling multiple events in one go."
        );
    }

    /**
     * Schedule a {@link MessageStream.Entry} for processing by this work package. Will immediately disregard the given
     * {@code eventEntry} if its {@link TrackingToken} is covered by the previously scheduled event.
     * <p>
     * <b>Threading note:</b> This method is and should only to be called by the {@link Coordinator} thread of a {@link
     * PooledStreamingEventProcessor}.
     *
     * @param eventEntry The event entry to schedule for work in this work package.
     * @return {@code True} if this {@code WorkPackage} scheduled the event for execution, otherwise {@code false}.
     */
    public boolean scheduleEvent(MessageStream.Entry<? extends EventMessage> eventEntry) {
        TrackingToken eventToken = TrackingToken.fromContext(eventEntry).orElse(null);
        EventMessage message = eventEntry.message();
        if (shouldNotSchedule(eventEntry)) {
            logger.trace("Ignoring event [{}] with position [{}] for work package [{}]. "
                                 + "The last token [{}] covers event's token [{}].",
                         message.identifier(),
                         eventToken != null ? eventToken.position().orElse(-1) : -1,
                         segment.getSegmentId(),
                         lastDeliveredToken,
                         eventToken);
            return false;
        }

        logger.debug("Assigned event [{}] with position [{}] to work package [{}].",
                     message.identifier(),
                     eventToken != null ? eventToken.position().orElse(-1) : -1,
                     segment.getSegmentId());

        var canHandle = canHandleMessage(eventEntry);

        processingQueue.add(new DefaultProcessingEntry(eventEntry, canHandle));
        lastDeliveredToken = eventToken;
        // the worker must always be scheduled to ensure claims are extended
        scheduleWorker();

        return canHandle;
    }

    /**
     * Determines whether this {@code WorkPackage} can handle the given {@link MessageStream.Entry}. This method creates
     * a specialized {@link ProcessingContext} from the event entry to evaluate if the event should be processed by this
     * work package's {@link Segment}.
     * <p>
     * The method extracts resources from the {@code eventEntry} that were set by the {@link StreamableEventSource} to
     * make filtering decisions. Example: These resources include data like
     * {@link LegacyResources#AGGREGATE_IDENTIFIER_KEY}, which might be essential (while using the Aggreate based
     * approach) for event sequencing since Axon Framework 5.0.0 no longer embeds aggregate identifiers directly in
     * event messages.
     * <p>
     * The temporary {@link EventSchedulingProcessingContext} created here has limitations - it cannot access
     * configuration components or register lifecycle hooks. Its sole purpose is to provide resource access during event
     * filtering evaluation.
     * <p>
     * This method is called during event scheduling in {@link #scheduleEvent(MessageStream.Entry)} and
     * {@link #scheduleEvents(List)} to determine if events should be added to the processing queue.
     *
     * @param eventEntry The event entry containing the message and associated resources to evaluate.
     * @return {@code true} if this {@code WorkPackage} can handle the event for processing, {@code false} otherwise
     * @see #canHandle(EventMessage, ProcessingContext)
     * @see EventSchedulingProcessingContext
     */
    private boolean canHandleMessage(MessageStream.Entry<? extends EventMessage> eventEntry) {
        var processingContext =
                Message.addToContext(
                        copyResources(eventEntry, schedulingProcessingContextProvider.get()),
                        eventEntry.message()
                );
        return canHandle(eventEntry.message(), processingContext);
    }

    private static ProcessingContext copyResources(Context from, ProcessingContext to) {
        //noinspection unchecked
        from.resources().forEach((k, v) -> to.putResource((Context.ResourceKey<Object>) k, v));
        return to;
    }

    /**
     * The given {@code eventEntry} should not be scheduled if the {@link TrackingToken} extracted from its context
     * {@link TrackingToken#covers(TrackingToken)} the last delivered token.
     * <p>
     * This validation ensures events that this work package already covered are ignored.
     *
     * @param eventEntry The event entry to validate whether it should be scheduled yes or no.
     * @return {@code true} if the given {@code eventEntry} should not be scheduled, {@code false} otherwise.
     */
    private boolean shouldNotSchedule(MessageStream.Entry<? extends EventMessage> eventEntry) {
        TrackingToken eventToken = TrackingToken.fromContext(eventEntry).orElse(null);
        // Null check is done to solve potential NullPointerException.
        return lastDeliveredToken != null && eventToken != null && lastDeliveredToken.covers(eventToken);
    }

    private boolean canHandle(EventMessage eventMessage, ProcessingContext processingContext) {
        try {
            return eventFilter.canHandle(eventMessage, processingContext, segment);
        } catch (Exception e) {
            logger.warn("Error while detecting whether event can be handled in Work Package [{}]-[{}]. "
                                + "Aborting Work Package...",
                        segment.getSegmentId(), name, e);
            abort(e);
            return false;
        }
    }

    /**
     * Schedule this {@code WorkPackage} to process its batch of scheduled events in a dedicated thread.
     * <p>
     * <b>Threading note:</b> This method is safe to be called by both the {@link Coordinator} threads and {@link
     * WorkPackage} threads of a {@link PooledStreamingEventProcessor}.
     */
    public void scheduleWorker() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        logger.debug("Scheduling Work Package [{}]-[{}] to process events.", segment.getSegmentId(), name);
        executorService.submit(this::runWorker);
    }

    private void runWorker() {
        CompletableFuture<Throwable> aborting = abortFlag.get();
        if (aborting != null) {
            logger.debug("Work Package [{}]-[{}] should be aborted. Will shutdown this work package.",
                         segment.getSegmentId(), name);
            segmentStatusUpdater.accept(previousStatus -> null);
            aborting.complete(abortException.get());
            return;
        }
        processEvents().whenCompleteAsync((unused, e) -> onProcessingComplete(e), executorService);
    }

    private static @Nullable TrackingToken lowerBound(Collection<TrackingToken> tokens) {
        return tokens.stream()
                     .filter(Objects::nonNull)
                     .reduce(TrackingToken::lowerBound)
                     .orElse(null);
    }

    private static @Nullable TrackingToken upperBound(Collection<TrackingToken> tokens) {
        return tokens.stream()
                     .filter(Objects::nonNull)
                     .reduce(TrackingToken::upperBound)
                     .orElse(null);
    }

    private void onProcessingComplete(@Nullable Throwable e) {
        if (e != null) {
            Throwable cause = e instanceof CompletionException ce ? ce.getCause() : e;
            logger.warn("Error while processing batch in Work Package [{}]-[{}]. Aborting Work Package...",
                        segment.getSegmentId(), name, cause);
            abort(cause);
        }
        scheduled.set(false);
        // Re-check ALL outstanding state AFTER releasing the flag, including a pending checkpoint request. This closes
        // the race where a checkpoint request arrives (and its scheduleWorker CAS loses) in the window after the worker
        // consumed the previous request but before it cleared the scheduled flag.
        if (!processingQueue.isEmpty() || abortFlag.get() != null || requestedCheckpoint.get() != null) {
            logger.debug("Rescheduling Work Package [{}]-[{}] since there are events or a checkpoint left.",
                         segment.getSegmentId(), name);
            scheduleWorker();
        }
    }

    private CompletableFuture<Void> processEvents() {
        List<MessageStream.Entry<? extends EventMessage>> eventBatch = new ArrayList<>();
        while (!isAbortTriggered() && eventBatch.size() < batchSize && !processingQueue.isEmpty()) {
            ProcessingEntry entry = processingQueue.poll();
            lastConsumedToken = WrappedToken.advance(lastConsumedToken, entry.trackingToken());
            entry.addToBatch(eventBatch, lastConsumedToken);
        }

        // Make sure all subsequent events with the same token (if non-null) as the last are added as well.
        // These are the result of upcasting and should always be processed in the same batch.

        if (eventBatch.isEmpty()) {
            // Nothing to handle: store a pending checkpoint (if one is due) in its own transaction, else keep the claim.
            segmentStatusUpdater.accept(status -> status.advancedTo(lastConsumedToken));
            return hasPendingCheckpoint()
                    ? unitOfWorkFactory.create().executeWithResult(this::checkpoint)
                    : extendClaimIfThresholdIsMet();
        }

        logger.debug("Work Package [{}]-[{}] is processing a batch of {} events.",
                     segment.getSegmentId(), name, eventBatch.size());
        processingEvents.set(true);
        var unitOfWork = unitOfWorkFactory.create();
        unitOfWork.runOnPreInvocation(ctx -> {
            ctx.putResource(Segment.RESOURCE_KEY, segment);
            ctx.putResource(TrackingToken.BATCH_END_RESOURCE_KEY, lastConsumedToken);
            if (!checkpointingParticipants.isEmpty()) {
                // Expose the per-segment trigger so a handler may request checkpoints via a CheckpointTrigger parameter.
                ctx.putResource(CheckpointTrigger.RESOURCE_KEY, checkpointTrigger);
            }
        });
        unitOfWork.onInvocation(ctx -> batchProcessor.process(eventBatch, ctx).asCompletableFuture());
        // One transaction handles the batch AND stores the checkpoint for this cycle (if any).
        unitOfWork.onPrepareCommit(this::checkpoint);
        unitOfWork.runOnAfterCommit(ctx -> {
            segmentStatusUpdater.accept(status -> status.advancedTo(lastConsumedToken));
            if (batchProcessedCallback != null) {
                batchProcessedCallback.run();
            }
        });
        CompletableFuture<Void> result;
        try {
            result = unitOfWork.execute();
        } catch (Exception e) {
            result = CompletableFuture.failedFuture(e);
        }
        return result.whenComplete((v, t) -> processingEvents.set(false));
    }

    /**
     * Determines whether an idle (empty-batch) cycle should still open a transaction to store a checkpoint.
     * <p>
     * An explicit request (from a {@link CheckpointTrigger}) is always honoured promptly. Otherwise, in auto mode, a
     * token advanced by ignored events is stored as a catch-up, throttled to the claim-extension cadence (exactly
     * today's behaviour for a processor without self-checkpointing components).
     */
    private boolean hasPendingCheckpoint() {
        if (requestedCheckpoint.get() != null) {
            return true;
        }
        return autoCheckpointing && lastStoredToken != lastConsumedToken && now() > nextClaimExtension.get();
    }

    /**
     * Stores this cycle's checkpoint, deciding <em>which</em> token (if any) to store. This is the single
     * store-decision shared by the auto-checkpointing and self-checkpointing paths.
     * <ul>
     *     <li><b>Auto mode</b> checkpoints at the batch-end token ({@code lastConsumedToken}); a self-checkpointing
     *     component co-located with an auto one is therefore forced to cover it.</li>
     *     <li><b>Fully-deferred mode</b> stores only when a component explicitly requested a checkpoint.</li>
     * </ul>
     * When there are no self-checkpointing {@link #checkpointingParticipants participants}, this stores the batch-end
     * token directly (the verbatim auto behaviour). Otherwise it {@link #reconcile(Map) reconciles} the participants to a
     * single agreed position and stores that, so no component is left ahead of the stored token. A store is skipped when
     * nothing is to be checkpointed or the result does not advance beyond the last stored token.
     */
    private CompletableFuture<Void> checkpoint(ProcessingContext ctx) {
        TrackingToken pending = requestedCheckpoint.getAndSet(null);
        // The auto component(s) are durable to the batch-end token within this transaction: cover at least there.
        TrackingToken requested = autoCheckpointing
                ? (pending == null || lastConsumedToken == null ? lastConsumedToken : pending.upperBound(lastConsumedToken))
                : pending;
        if (requested == null) {
            return emptyCompletedFuture();
        }
        if (checkpointingParticipants.isEmpty()) {
            return storeIfAdvanced(requested, ctx);
        }
        return requestEach(participant -> requestAdvance(participant, requested))
                .thenCompose(this::reconcile)
                .thenCompose(agreed -> storeIfAdvanced(agreed, ctx));
    }

    /**
     * Drives the participants to a <em>single agreed</em> checkpoint position and returns it.
     * <p>
     * Storing the {@link TrackingToken#lowerBound(TrackingToken) lowerBound} of differing reported positions would
     * leave any component that advanced <em>further</em> than the stored token having to reprocess the events between
     * the stored token and its own position on the next claim -- events it already made durable, which is only safe if
     * that processing is idempotent. To avoid relying on idempotency, this reconciles the reported positions: it takes
     * the highest reported position and re-requests every component that has not yet reached it (through
     * {@link Checkpointing#onCheckpointAdvanced(Segment, TrackingToken)}), repeating until every component reports the
     * same position.
     * <p>
     * This terminates: the agreed position only ever rises and is bounded by {@code lastConsumedToken} (a component
     * cannot be durable past what it was handed). A component that cannot reach the agreed position fails its future,
     * which fails the surrounding checkpoint without storing -- the safe outcome.
     *
     * @param reported the latest position reported by each participant
     * @return the single position every participant has durably reached (or {@code null} if none reported one)
     */
    private CompletableFuture<TrackingToken> reconcile(Map<Checkpointing, TrackingToken> reported) {
        TrackingToken agreed = upperBound(reported.values());
        if (agreed == null || reported.size() == 1) {
            // Nothing reported, or a single participant that trivially agrees with itself: no reconciliation needed.
            return CompletableFuture.completedFuture(agreed);
        }
        List<Checkpointing> laggards =
                reported.entrySet()
                        .stream()
                        .filter(entry -> entry.getValue() == null || !entry.getValue().covers(agreed))
                        .map(Map.Entry::getKey)
                        .toList();
        if (laggards.isEmpty()) {
            return CompletableFuture.completedFuture(agreed);
        }
        // Re-request the laggards to also reach `agreed` (keeping the others' positions), then reconcile again.
        // `agreed` only rises (bounded by lastConsumedToken), so this converges; a laggard that cannot reach it fails
        // the checkpoint via requestAdvance.
        return requestEach(participant -> laggards.contains(participant)
                ? requestAdvance(participant, agreed)
                : CompletableFuture.completedFuture(reported.get(participant)))
                .thenCompose(this::reconcile);
    }

    /**
     * Asks a single participant to ensure it is durable up to {@code target} and returns the (LATEST-resolved,
     * cover-validated) position it reports.
     */
    private CompletableFuture<TrackingToken> requestAdvance(Checkpointing participant, TrackingToken target) {
        return participant.onCheckpointAdvanced(segment, target)
                          .thenApply(this::resolveLatest)
                          .thenApply(actual -> validateCovers(participant, actual, target));
    }

    /**
     * Enforces the actual-covers-requested contract; a violation (including a {@code null} report) fails the checkpoint
     * (no store).
     */
    private TrackingToken validateCovers(Checkpointing participant,
                                         @Nullable TrackingToken actual,
                                         TrackingToken requested) {
        if (actual == null || !actual.covers(requested)) {
            throw new IllegalStateException(
                    "Checkpointing component [" + participant + "] returned checkpoint token [" + actual
                            + "] that does not cover the requested position [" + requested + "]."
            );
        }
        return actual;
    }

    /**
     * Records a checkpoint request from a {@link CheckpointTrigger}, forward-merging {@code token} into the pending
     * segment-scoped request via {@link TrackingToken#upperBound(TrackingToken)} and waking the worker. Ignored once
     * the segment is released, or when {@code token} is {@code null} (nothing safe yet).
     */
    private void onCheckpointRequested(@Nullable TrackingToken token) {
        TrackingToken resolved = resolveLatest(token);
        if (resolved == null || checkpointsReleased.get()) {
            // segment released, or nothing handled yet: a late async ack has no safe point to record -- ignore it
            return;
        }
        requestedCheckpoint.accumulateAndGet(resolved,
                                             (current, next) -> current == null ? next : current.upperBound(next));
        scheduleWorker();
    }

    /**
     * Resolves the {@link TrackingToken#LATEST} sentinel to this package's {@code lastConsumedToken} -- the latest
     * position actually handed to the handler. A component may use {@code LATEST} (via the {@link CheckpointTrigger} or
     * as a return value) to mean "checkpoint as far as you have given me" without tracking a concrete token; it is
     * deliberately <em>not</em> interpreted as the end of the stream (which this package may not have reached). Any
     * other token is returned unchanged, so the sentinel never reaches the token algebra or the {@link TokenStore}.
     */
    private @Nullable TrackingToken resolveLatest(@Nullable TrackingToken token) {
        return TrackingToken.LATEST.equals(token) ? lastConsumedToken : token;
    }

    /**
     * Hands the {@link CheckpointTrigger} for this package's {@link Segment} to every self-checkpointing
     * {@link #checkpointingParticipants participant}. Invoked by the {@link Coordinator} once the segment is claimed.
     */
    void notifySegmentClaimed() {
        checkpointingParticipants.forEach(participant -> participant.onSegmentClaimed(segment, checkpointTrigger));
    }

    /**
     * Performs the final checkpoint for a segment being released: asks each self-checkpointing
     * {@link #checkpointingParticipants participant} to drain toward {@code lastConsumedToken} through
     * {@link Checkpointing#onSegmentReleased(Segment, TrackingToken)}, then {@link #reconcile(Map) reconciles} their
     * reported positions to a single agreed token -- the highest any participant reported, driving the others to reach
     * it (exactly as {@link #checkpoint(ProcessingContext)} does) -- and stores that within the given {@code ctx} (if it
     * advances). Only when reconciliation cannot be reached -- a lagging participant fails to cover the agreed position
     * -- does it fall back to storing the {@link TrackingToken#lowerBound(TrackingToken) lowerBound} of the reported
     * tokens, so the claim can still be released and the uncovered tail is simply reprocessed on the next claim.
     * Invoked by the {@link Coordinator} while the token-store claim is still held, before the claim is released. With no
     * participant it is a no-op (the per-batch store already covered progress).
     */
    CompletableFuture<Void> checkpointOnRelease(ProcessingContext ctx) {
        if (checkpointingParticipants.isEmpty()) {
            return emptyCompletedFuture();
        }
        return requestEach(participant -> participant.onSegmentReleased(segment, lastConsumedToken)
                                                     .thenApply(this::resolveLatest))
                .thenCompose(reported -> reconcile(reported).exceptionally(error -> {
                    // The claim must still be released: if the components cannot be reconciled, fall back to the
                    // lowest reported safe token (which may cause idempotent reprocessing of the gap on the next claim).
                    logger.warn("Work Package [{}]-[{}] could not reconcile the release checkpoint across components; "
                                        + "storing the lowest reported safe token.",
                                name, segment.getSegmentId(), error);
                    return lowerBound(reported.values());
                }))
                .thenCompose(agreed -> storeIfAdvanced(agreed, ctx))
                // The claim must be released regardless of whether a final token could be stored: if a component's
                // release future failed (so no safe token could even be determined) or the store itself failed, leave
                // the stored token where it is and let the uncovered tail be reprocessed from there on the next claim.
                // Completing normally here ensures the Coordinator still releases the claim.
                .exceptionally(error -> {
                    logger.warn("Work Package [{}]-[{}] failed to store a final checkpoint on release; releasing the "
                                        + "claim without advancing the stored token. The uncovered tail will be "
                                        + "reprocessed on the next claim.",
                                name, segment.getSegmentId(), error);
                    return null;
                });
    }

    /**
     * Invokes {@code request} on every participant concurrently, returning their reported tokens keyed by participant.
     */
    private CompletableFuture<Map<Checkpointing, TrackingToken>> requestEach(
            Function<Checkpointing, CompletableFuture<TrackingToken>> request
    ) {
        Map<Checkpointing, CompletableFuture<TrackingToken>> futures = new LinkedHashMap<>();
        checkpointingParticipants.forEach(participant -> futures.put(participant, request.apply(participant)));
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                                .thenApply(ignored -> {
                                    Map<Checkpointing, TrackingToken> reported = new LinkedHashMap<>();
                                    futures.forEach((participant, future) -> reported.put(participant, future.join()));
                                    return reported;
                                });
    }

    /**
     * Extend the claim of the {@link TrackingToken} owned by this {@code WorkPackage}, if the configurable
     * {@link PooledStreamingEventProcessorConfiguration#claimExtensionThreshold(long) claim extension threshold} is
     * met.
     */
    public CompletableFuture<Void> extendClaimIfThresholdIsMet() {
        if (now() > nextClaimExtension.get()) {
            logger.debug("Work Package [{}]-[{}] will extend its token claim.", name, segment.getSegmentId());
            return unitOfWorkFactory.create().executeWithResult(
                    context -> tokenStore.extendClaim(name, segment.getSegmentId(), context)
            ).thenRun(() -> nextClaimExtension.set(now() + claimExtensionThreshold));
        }
        return emptyCompletedFuture();
    }

    /**
     * Indicates whether {@code candidate} represents a position at or beyond {@code reference}, comparing the raw
     * {@link WrappedToken#unwrapUpperBound(TrackingToken) unwrapped} upper-bound positions rather than the tokens
     * directly.
     * <p>
     * Unwrapping keeps the comparison robust when a replay concludes: {@code reference} may still be a
     * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken} while {@code candidate}
     * is the plain stream token it advances to, and a direct {@code candidate.covers(reference)} would then either
     * throw (the raw token type rejecting the wrapped one) or report a false regression. Comparing the unwrapped
     * positions reports that transition as an advance, while still returning {@code false} for a genuine regression or
     * an incomparable token (such as a partially-regressed multi-source token).
     *
     * @param candidate the token whose position is tested
     * @param reference the token to compare against
     * @return {@code true} if {@code candidate} covers {@code reference} once both are unwrapped to their raw
     * upper-bound positions
     */
    private static boolean coversWhenUnwrapped(TrackingToken candidate, TrackingToken reference) {
        return WrappedToken.unwrapUpperBound(candidate).covers(WrappedToken.unwrapUpperBound(reference));
    }

    /**
     * Stores {@code safe}, keeping the stored {@link TrackingToken} monotonic. A {@code null} or already-stored token
     * is ignored. A token that does not {@link #coversWhenUnwrapped(TrackingToken, TrackingToken) cover} the last
     * stored checkpoint -- whether a strict regression or an <em>incomparable</em> position, such as a
     * partially-regressed multi-source token where one source advanced while another fell behind -- is ignored with a
     * warning rather than persisted, so a misbehaving component can never rewind progress on any source. A concluding
     * replay is still recognized as an advance, as the coverage is judged on the unwrapped positions.
     */
    private CompletableFuture<Void> storeIfAdvanced(@Nullable TrackingToken safe, ProcessingContext ctx) {
        if (safe == null || safe.equals(lastStoredToken)) {
            return emptyCompletedFuture();
        }
        if (lastStoredToken != null && !coversWhenUnwrapped(safe, lastStoredToken)) {
            logger.warn(
                    "Work Package [{}]-[{}] ignoring checkpoint token [{}]; it does not advance beyond the stored token [{}].",
                    name,
                    segment.getSegmentId(),
                    safe,
                    lastStoredToken);
            return emptyCompletedFuture();
        }
        return storeToken(safe, ctx);
    }

    private CompletableFuture<Void> storeToken(TrackingToken token, ProcessingContext processingContext) {
        logger.debug("Work Package [{}]-[{}] will store token [{}].", name, segment.getSegmentId(), token);
        return tokenStore.storeToken(token, name, segment.getSegmentId(), processingContext)
                         .thenRun(() -> {
                             lastStoredToken = token;
                             nextClaimExtension.set(now() + claimExtensionThreshold);
                         });
    }

    /**
     * Indicates whether this {@code WorkPackage} has any processing capacity remaining, or whether it has reached its
     * soft limit. Note that one can still deliver events for processing in this {@code WorkPackage}.
     *
     * @return {@code true} if the {@code WorkPackage} has remaining capacity, or {@code false} if the soft limit has
     * been reached
     */
    public boolean hasRemainingCapacity() {
        return this.processingQueue.size() < BUFFER_SIZE;
    }

    /**
     * Indicates whether this {@code WorkPackage} has any work in the queue or scheduled.
     *
     * @return {@code true} if the {@code processingQueue} is empty and there is nothing scheduled, or {@code false}
     * otherwise.
     */
    public boolean isDone() {
        return this.processingQueue.isEmpty() && !this.scheduled.get();
    }

    /**
     * Returns the {@link Segment} that this {@code WorkPackage} is processing events for.
     *
     * @return the {@link Segment} that this {@code WorkPackage} is processing events for
     */
    public Segment segment() {
        return segment;
    }

    /**
     * Returns the {@link TrackingToken} of the {@link MessageStream.Entry} that was delivered in the last
     * {@link WorkPackage#scheduleEvent(MessageStream.Entry)} call.
     * <p>
     * <b>Threading note:</b> This method is only safe to call from {@link Coordinator} threads. The {@link
     * WorkPackage} threads must not rely on this method.
     *
     * @return the {@link TrackingToken} of the last {@link MessageStream.Entry} that was delivered to this
     * {@code WorkPackage}
     */
    public TrackingToken lastDeliveredToken() {
        return lastDeliveredToken;
    }

    /**
     * Indicates whether an abort has been triggered for this {@code WorkPackage}. When {@code true}, any events
     * scheduled for processing by this {@code WorkPackage} are likely to be ignored.
     * <p>
     * Use {@link WorkPackage#abort(Throwable)} (possibly with a {@code null} reason) to obtain a
     * {@link CompletableFuture} with a reference to the abort reason.
     *
     * @return {@code true} if an abort was scheduled, otherwise {@code false}
     */
    public boolean isAbortTriggered() {
        return abortFlag.get() != null;
    }

    /**
     * Marks this {@code WorkPackage} as <em>aborted</em>. The returned {@link CompletableFuture} is completed with the
     * abort reason once the {@code WorkPackage} has finished any processing that may had been started already.
     * <p>
     * If this {@code WorkPackage} was already aborted in another request, the returned {@code CompletableFuture} will
     * complete with the first abort reason.
     * <p>
     * An aborted {@code WorkPackage} cannot be restarted.
     *
     * @param abortReason the reason to request the {@code WorkPackage} to abort, may be {@code null}
     * @return a {@link CompletableFuture} that completes with the first reason once the {@code WorkPackage} has stopped
     * processing
     */
    public CompletableFuture<Throwable> abort(@Nullable Throwable abortReason) {
        // Deactivate the CheckpointTrigger before the release sequence runs: any late requestCheckpoint becomes a
        // no-op. The final safe token is taken solely from the onSegmentReleased return value (see checkpointOnRelease).
        checkpointsReleased.set(true);
        if (abortReason != null) {
            logger.debug("Abort request received for Work Package [{}]-[{}].",
                         name, segment.getSegmentId(), abortReason);
            segmentStatusUpdater.accept(
                    status -> {
                        if (status != null) {
                            return status.isErrorState() ? status : status.markError(abortReason);
                        }
                        return null;
                    }
            );
        }

        CompletableFuture<Throwable> abortTask = Objects.requireNonNull(abortFlag.updateAndGet(
                currentFlag -> {
                    if (currentFlag == null) {
                        abortException.set(abortReason);
                        return new CompletableFuture<>();
                    } else {
                        abortException.updateAndGet(
                                currentReason -> currentReason == null ? abortReason : currentReason
                        );
                        return currentFlag;
                    }
                }
        ));
        // Reschedule the worker to ensure the abort flag is processed
        scheduleWorker();
        return abortTask;
    }

    /**
     * Lambda to be invoked whenever the event batch of this package's {@code segment} processed.
     *
     * @param batchProcessedCallback lambda to be invoked whenever the event batch of this package's {@code segment}
     *                               processed
     */
    void onBatchProcessed(Runnable batchProcessedCallback) {
        this.batchProcessedCallback = batchProcessedCallback;
    }

    /**
     * Returns whether this {@code WorkPackage} is actively processing events.
     *
     * @return Whether this {@code WorkPackage} is actively processing events.
     */
    public boolean isProcessingEvents() {
        return processingEvents.get();
    }

    /**
     * Functional interface defining a validation if a given {@link EventMessage} can be handled within the given
     * {@link Segment}.
     */
    @FunctionalInterface
    interface EventFilter {

        /**
         * Indicates whether the work package can handle the given {@code eventMessage} for the given {@code segment}.
         *
         * @param eventMessage the message for which to identify if the work package can handle it
         * @param context      the {@link ProcessingContext} to use
         * @param segment      the segment for which the event can be processed
         * @return {@code true} if the event message can be handled, otherwise {@code false}
         * @throws Exception when validating of the given {@code eventMessage} fails
         */
        boolean canHandle(EventMessage eventMessage, ProcessingContext context, Segment segment) throws Exception;
    }

    /**
     * Functional interface defining the processing of a batch of {@link EventMessage}s within a
     * {@link ProcessingContext}.
     */
    @FunctionalInterface
    interface BatchProcessor {

        /**
         * Processes a batch of entries in the processing context.
         *
         * @param entries  The batch of event messages to be processed.
         * @param context The processing context in which the event messages are processed.
         * @return A stream of messages resulting from the processing of the event messages.
         */
        MessageStream.Empty<Message> process(List<MessageStream.Entry<? extends EventMessage>> entries,
                                             ProcessingContext context);
    }

    /**
     * Package private builder class to construct a {@code WorkPackage}. Not used for validation of the fields as is the
     * case with most builders, but purely to clarify the construction of a {@code WorkPackage}.
     */
    static class Builder {

        private @Nullable String name;
        private @Nullable TokenStore tokenStore;
        private @Nullable UnitOfWorkFactory unitOfWorkFactory;
        private @Nullable ExecutorService executorService;
        private @Nullable EventFilter eventFilter;
        private @Nullable BatchProcessor batchProcessor;
        private @Nullable Segment segment;
        private @Nullable TrackingToken initialToken;
        private int batchSize = 1;
        private long claimExtensionThreshold = 5000;
        private boolean autoCheckpointing = true;
        private List<Checkpointing> checkpointingParticipants = List.of();
        private @Nullable Consumer<UnaryOperator<TrackerStatus>> segmentStatusUpdater;
        @Deprecated(forRemoval = true, since = "5.2.0")
        private Clock clock = ClockUtils.get();
        private Supplier<ProcessingContext> schedulingProcessingContextProvider = () ->
                new EventSchedulingProcessingContext(EmptyApplicationContext.INSTANCE);

        /**
         * The {@code name} of the processor this {@code WorkPackage} processes events for.
         *
         * @param name the name of the processor this {@code WorkPackage} processes events for
         * @return the current Builder instance, for fluent interfacing
         */
        Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * The storage solution of {@link TrackingToken}s. Used to extend claims on and update the
         * {@code initialToken}.
         *
         * @param tokenStore the storage solution of {@link TrackingToken}s
         * @return the current Builder instance, for fluent interfacing
         */
        Builder tokenStore(TokenStore tokenStore) {
            this.tokenStore = tokenStore;
            return this;
        }

        /**
         * A {@link UnitOfWorkFactory} used to invoke {@link TokenStore} operations and event processing inside a
         * {@link UnitOfWork} (you may use
         * {@link TransactionalUnitOfWorkFactory to execute those operations transactionally}.
         *
         * @param unitOfWorkFactory a factory for {@link UnitOfWork} used to invoke {@link TokenStore} operations and
         *                          event processing
         * @return the current Builder instance, for fluent interfacing
         */
        Builder unitOfWorkFactory(UnitOfWorkFactory unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            return this;
        }

        /**
         * A {@link ExecutorService} used to run this work package's tasks in.
         *
         * @param executorService a {@link ExecutorService} used to run this work package's tasks in
         * @return the current Builder instance, for fluent interfacing
         */
        Builder executorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * Checks whether a buffered event can be handled by this package's {@code segment}.
         *
         * @param eventFilter checks whether a buffered event can be handled by this package's {@code segment}
         * @return the current Builder instance, for fluent interfacing
         */
        Builder eventFilter(EventFilter eventFilter) {
            this.eventFilter = eventFilter;
            return this;
        }

        /**
         * A processor of a batch of events.
         *
         * @param batchProcessor processes a batch of events
         * @return the current Builder instance, for fluent interfacing
         */
        Builder batchProcessor(BatchProcessor batchProcessor) {
            this.batchProcessor = batchProcessor;
            return this;
        }

        /**
         * The {@link Segment} this work package is in charge of.
         *
         * @param segment the {@link Segment} this work package is in charge of
         * @return the current Builder instance, for fluent interfacing
         */
        Builder segment(Segment segment) {
            this.segment = segment;
            return this;
        }

        /**
         * The initial {@link TrackingToken} when this package starts processing events.
         *
         * @param initialToken the initial {@link TrackingToken} when this package starts processing events
         * @return the current Builder instance, for fluent interfacing
         */
        Builder initialToken(TrackingToken initialToken) {
            this.initialToken = initialToken;
            return this;
        }

        /**
         * The amount of events to be processed in a single batch. Defaults to {@code 1};
         *
         * @param batchSize the amount of events to be processed in a single batch
         * @return the current Builder instance, for fluent interfacing
         */
        Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * The time in milliseconds after which the claim of the {@link TrackingToken} will be extended. Will only be
         * used in absence of regular token update through event processing. Defaults to {@code 5000};
         *
         * @param claimExtensionThreshold the time in milliseconds after which the claim of the {@link TrackingToken}
         *                                will be extended
         * @return the current Builder instance, for fluent interfacing
         */
        Builder claimExtensionThreshold(long claimExtensionThreshold) {
            this.claimExtensionThreshold = claimExtensionThreshold;
            return this;
        }

        /**
         * Whether this {@code WorkPackage} runs in auto-checkpointing mode. {@code true} (the default) means a
         * checkpoint at the batch-end token is requested every batch on behalf of the auto component(s); {@code false}
         * (fully-deferred) means the stored token advances only on explicit {@link CheckpointTrigger} request.
         *
         * @param autoCheckpointing {@code true} when at least one component is auto-checkpointing, {@code false} when
         *                          every component is self-checkpointing
         * @return the current Builder instance, for fluent interfacing
         */
        Builder autoCheckpointing(boolean autoCheckpointing) {
            this.autoCheckpointing = autoCheckpointing;
            return this;
        }

        /**
         * The self-checkpointing units (resolved via {@code unwrap(Checkpointing.class)}) that participate in this
         * package's checkpoints. When empty, the {@code WorkPackage} takes the verbatim auto-checkpointing fast path.
         *
         * @param checkpointingParticipants the self-checkpointing units of this package's processor
         * @return the current Builder instance, for fluent interfacing
         */
        Builder checkpointingParticipants(List<Checkpointing> checkpointingParticipants) {
            this.checkpointingParticipants = checkpointingParticipants;
            return this;
        }

        /**
         * Lambda to be invoked whenever the status of this package's {@code segment} changes.
         *
         * @param segmentStatusUpdater lambda to be invoked whenever the status of this package's {@code segment}
         *                             changes
         * @return the current Builder instance, for fluent interfacing
         */
        Builder segmentStatusUpdater(Consumer<UnaryOperator<TrackerStatus>> segmentStatusUpdater) {
            this.segmentStatusUpdater = segmentStatusUpdater;
            return this;
        }

        /**
         * Defines the {@link Clock} used for time dependent operations. For example used to update whenever this
         * {@code WorkPackage} updated the {@link TrackingToken} claim last.
         *
         * @param clock the {@link Clock} used for time dependent operations
         * @return the current Builder instance, for fluent interfacing
         * @deprecated Use {@link ClockUtils#set(Clock)} if you have to provide a non-default {@link Clock} instance.
         */
        @Deprecated(forRemoval = true, since = "5.2.0")
        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Provides a {@link ProcessingContext} used to evaluate whether an event can be scheduled for processing by
         * this {@code WorkPackage}. The provided {@code ProcessingContext} is enriched with resources from the
         * {@link MessageStream.Entry} to evaluate whether the event can be handled by this package's {@link Segment}.
         * Currently, the only usage of the context is for
         * {@link EventHandlingComponent#sequenceIdentifierFor(EventMessage, ProcessingContext)} execution.
         *
         * @param schedulingProcessingContextProvider The {@link ProcessingContext} provider.
         * @return The current Builder instance, for fluent interfacing.
         */
        Builder schedulingProcessingContextProvider(
                Supplier<ProcessingContext> schedulingProcessingContextProvider
        ) {
            Objects.requireNonNull(schedulingProcessingContextProvider,
                                   "schedulingProcessingContextProvider may not be null.");
            this.schedulingProcessingContextProvider = schedulingProcessingContextProvider;
            return this;
        }

        /**
         * Initializes a {@code WorkPackage} as specified through this Builder.
         *
         * @return a {@code WorkPackage} as specified through this Builder
         */
        WorkPackage build() {
            return new WorkPackage(this);
        }
    }

    /**
     * Marker interface defining a unit of work containing one or more event messages to be processed by this work
     * package.
     */
    private interface ProcessingEntry {

        /**
         * Return the position of this processing entry.
         *
         * @return The position of this processing entry.
         */
        TrackingToken trackingToken();

        /**
         * Add this entry's events to the {@code eventBatch}, overriding the raw stream token already present in the
         * entry's context with the given {@code wrappedToken}.
         * <p>
         * The raw token stored in the entry's context is the position as reported by the event source (e.g. a plain
         * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken}).
         * That raw value is insufficient for correct per-event processing: {@code wrappedToken} is the result of
         * {@link WrappedToken#advance(TrackingToken, TrackingToken)} applied to the previous batch position and this
         * entry's raw token, so it encodes additional state — most importantly, during a replay it is a
         * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken} wrapping the raw
         * position. Without this override, replay-detection logic such as
         * {@link
         * org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken#isReplay(TrackingToken)} and
         * {@link
         * org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken#concludesReplay(TrackingToken)}
         * would always see a plain token and never recognise the replay boundary, causing all events in a batch to be
         * mis-classified.
         *
         * @param eventBatch   the list of events to add this entry's events to
         * @param wrappedToken the result of {@link WrappedToken#advance} for this specific event; replaces the raw
         *                     stream token in each event's context
         */
        void addToBatch(List<MessageStream.Entry<? extends EventMessage>> eventBatch, TrackingToken wrappedToken);
    }

    /**
     * Container of a {@link MessageStream.Entry} and {@code boolean} whether the given {@code eventMessage} can be
     * handled in this package. The combination constitutes to a processing entry the {@code WorkPackage} should
     * ingest.
     */
    private record DefaultProcessingEntry(MessageStream.Entry<? extends EventMessage> eventEntry, boolean canHandle)
            implements ProcessingEntry {

        @Override
        public TrackingToken trackingToken() {
            return TrackingToken.fromContext(eventEntry).orElse(null);
        }

        @Override
        public void addToBatch(
                List<MessageStream.Entry<? extends EventMessage>> eventBatch,
                TrackingToken wrappedToken
        ) {
            if (canHandle) {
                eventBatch.add(eventEntry.withResource(TrackingToken.RESOURCE_KEY, wrappedToken));
            }
        }
    }

    /**
     * Container of a batch of {@link ProcessingEntry ProcessingEntries}. These entries are grouped together since they
     * should be handled within a single batch by the work package.
     */
    private static class BatchProcessingEntry implements ProcessingEntry {

        private final List<ProcessingEntry> processingEntries;

        public BatchProcessingEntry() {
            this.processingEntries = new ArrayList<>();
        }

        public void add(ProcessingEntry processingEntry) {
            processingEntries.add(processingEntry);
        }

        @Override
        public TrackingToken trackingToken() {
            return processingEntries.getFirst().trackingToken();
        }

        @Override
        public void addToBatch(
                List<MessageStream.Entry<? extends EventMessage>> eventBatch,
                TrackingToken wrappedToken
        ) {
            processingEntries.forEach(entry -> entry.addToBatch(eventBatch, wrappedToken));
        }
    }
}