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

package org.axonframework.messaging.eventhandling.processing.streaming.checkpoint;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.SegmentProgressContext;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.SegmentProgressStrategy;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingTokenUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;
import static org.axonframework.common.FutureUtils.emptyCompletedFuture;

/**
 * {@link SegmentProgressStrategy} that lets self-checkpointing {@link Checkpointing} units manage when their segment's
 * stored {@link TrackingToken} advances. The progress-persistence counterpart of the self-checkpointing protocol: it
 * collects the safe positions reported by the segment's participants, reconciles them to a single agreed token, and
 * persists that through the {@link SegmentProgressContext}.
 * <p>
 * It runs in one of two modes, fixed per processor at construction:
 * <ul>
 *     <li><b>Auto</b> ({@code autoCheckpointing == true}): at least one ordinary handler is co-located with the
 *     participants, so a checkpoint at the batch-end token is requested every batch and the participants are driven to
 *     cover it; they cannot defer the stored token.</li>
 *     <li><b>Fully-deferred</b> ({@code autoCheckpointing == false}): every handler is a participant, so the stored
 *     token advances only on an explicit {@link CheckpointTrigger} request.</li>
 * </ul>
 * <p>
 * <b>Internal API.</b> This class is marked {@link Internal}: it is part of the self-checkpointing support, intended
 * primarily for internal and advanced use, and its shape may change in a minor or patch release.
 *
 * @author Allard Buijze
 * @see Checkpointing
 * @see CheckpointTrigger
 * @since 5.2.0
 */
@Internal
public final class CheckpointingProgressStrategy implements SegmentProgressStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final SegmentProgressContext context;
    private final List<Checkpointing> participants;
    private final boolean autoCheckpointing;

    /**
     * The pending segment-scoped checkpoint request: the position any participant declared safe, forward-merged via
     * {@link TrackingToken#upperBound(TrackingToken)}. Set from arbitrary threads through the {@link CheckpointTrigger}
     * (and by this strategy itself every batch in auto mode); consumed on the worker thread.
     */
    private final AtomicReference<@Nullable TrackingToken> requestedCheckpoint = new AtomicReference<>();
    /**
     * Guards the {@link CheckpointTrigger} once the segment is released. Set during {@link #onAbort()}; once set, a
     * checkpoint request is a no-op (a late async-write acknowledgement has no safe point left to record).
     */
    private final AtomicBoolean checkpointsReleased = new AtomicBoolean();
    private final CheckpointTrigger trigger = this::onCheckpointRequested;

    /**
     * Constructs a {@code CheckpointingProgressStrategy} bound to the given {@code context}.
     *
     * @param context           the work package's progress context to persist progress through
     * @param participants      the self-checkpointing units on this segment; must not be empty
     * @param autoCheckpointing {@code true} when an ordinary handler is co-located (auto mode), {@code false} when every
     *                          handler is self-checkpointing (fully-deferred)
     */
    public CheckpointingProgressStrategy(SegmentProgressContext context,
                                         List<Checkpointing> participants,
                                         boolean autoCheckpointing) {
        this.context = requireNonNull(context, "The SegmentProgressContext may not be null.");
        this.participants = List.copyOf(requireNonNull(participants, "The participants may not be null."));
        this.autoCheckpointing = autoCheckpointing;
    }

    @Override
    public void contributeBatchResources(ProcessingContext processingContext) {
        // Expose the per-segment trigger so a handler may request checkpoints via a CheckpointTrigger parameter.
        processingContext.putResource(CheckpointTrigger.RESOURCE_KEY, trigger);
    }

    /**
     * Decides <em>which</em> token (if any) to store for this cycle and persists it through the context.
     * <ul>
     *     <li><b>Auto mode</b> checkpoints at the batch-end token ({@code lastConsumedToken}); a participant is
     *     therefore forced to cover it.</li>
     *     <li><b>Fully-deferred mode</b> stores only when a participant explicitly requested a checkpoint.</li>
     * </ul>
     * The participants are {@link #reconcile(Map) reconciled} to a single agreed position before storing, so no
     * participant is left ahead of the stored token. A store is skipped when nothing is to be checkpointed.
     */
    @Override
    public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
        TrackingToken pending = requestedCheckpoint.getAndSet(null);
        TrackingToken consumed = context.lastConsumedToken();
        // The auto component(s) are durable to the batch-end token within this transaction: cover at least there.
        TrackingToken requested = autoCheckpointing
                ? (pending == null || consumed == null ? consumed : pending.upperBound(consumed))
                : pending;
        if (requested == null) {
            return emptyCompletedFuture();
        }
        return requestEach(participant -> requestAdvance(participant, requested))
                .thenCompose(this::reconcile)
                .thenCompose(agreed -> context.persistProgress(agreed, processingContext));
    }

    @Override
    public boolean hasPendingWork() {
        // Out-of-band request signal only: a checkpoint requested between worker cycles needs a cycle to be stored.
        return requestedCheckpoint.get() != null;
    }

    @Override
    public void onSegmentClaimed() {
        Segment segment = context.segment();
        participants.forEach(participant -> participant.onSegmentClaimed(segment, trigger));
    }

    /**
     * Performs the final checkpoint for the segment being released: asks each participant to drain toward
     * {@code lastConsumedToken} through {@link Checkpointing#onSegmentReleased(Segment, TrackingToken)}, then
     * {@link #reconcile(Map) reconciles} their reported positions to a single agreed token and persists that within the
     * given {@code context} (if it advances). Only when reconciliation cannot be reached (a lagging participant fails to
     * cover the agreed position) does it fall back to persisting the
     * {@link TrackingTokenUtils#lowerBound(java.util.Collection) lowerBound} of the reported tokens, so the claim can still be
     * released and the uncovered tail is simply reprocessed on the next claim.
     */
    @Override
    public CompletableFuture<Void> onSegmentReleased(ProcessingContext processingContext) {
        Segment segment = context.segment();
        TrackingToken upTo = context.lastConsumedToken();
        return requestEach(participant -> participant.onSegmentReleased(segment, upTo)
                                                     .thenApply(this::resolveLatest))
                .thenCompose(reported -> reconcile(reported).exceptionally(error -> {
                    // The claim must still be released: if the components cannot be reconciled, fall back to the
                    // lowest reported safe token (which may cause idempotent reprocessing of the gap on the next claim).
                    logger.warn("Could not reconcile the release checkpoint for {} across components; "
                                        + "storing the lowest reported safe token.",
                                segment, error);
                    return TrackingTokenUtils.lowerBound(reported.values());
                }))
                .thenCompose(agreed -> context.persistProgress(agreed, processingContext))
                // The claim must be released regardless of whether a final token could be stored: if a component's
                // release future failed (so no safe token could even be determined) or the store itself failed, leave
                // the stored token where it is and let the uncovered tail be reprocessed from there on the next claim.
                // Completing normally here ensures the Coordinator still releases the claim.
                .exceptionally(error -> {
                    logger.warn("Failed to store a final checkpoint on release of {}; releasing the claim without "
                                        + "advancing the stored token. The uncovered tail will be reprocessed on the "
                                        + "next claim.",
                                segment, error);
                    return null;
                });
    }

    @Override
    public void onAbort() {
        // Deactivate the CheckpointTrigger before the release sequence runs: any late requestCheckpoint becomes a
        // no-op. The final safe token is taken solely from the onSegmentReleased return value.
        checkpointsReleased.set(true);
    }

    /**
     * Records a checkpoint request from a {@link CheckpointTrigger}, forward-merging {@code token} into the pending
     * segment-scoped request via {@link TrackingToken#upperBound(TrackingToken)} and waking the worker. Ignored once the
     * segment is released, or when {@code token} resolves to {@code null} (nothing safe yet).
     */
    private void onCheckpointRequested(@Nullable TrackingToken token) {
        TrackingToken resolved = resolveLatest(token);
        if (resolved == null || checkpointsReleased.get()) {
            // segment released, or nothing handled yet: a late async ack has no safe point to record, so ignore it
            return;
        }
        requestedCheckpoint.accumulateAndGet(resolved,
                                             (current, next) -> current == null ? next : current.upperBound(next));
        context.scheduleWorker();
    }

    /**
     * Drives the participants to a <em>single agreed</em> checkpoint position and returns it.
     * <p>
     * Storing the {@link TrackingToken#lowerBound(TrackingToken) lowerBound} of differing reported positions would leave
     * any component that advanced <em>further</em> than the stored token having to reprocess the events between the
     * stored token and its own position on the next claim (events it already made durable), which is only safe if that
     * processing is idempotent. To avoid relying on idempotency, this reconciles the reported positions: it takes the
     * highest reported position and re-requests every component that has not yet reached it (through
     * {@link Checkpointing#onCheckpointAdvanced(Segment, TrackingToken)}), repeating until every component reports the
     * same position.
     * <p>
     * This terminates: the agreed position only ever rises and is bounded by {@code lastConsumedToken} (a component
     * cannot be durable past what it was handed). A component that cannot reach the agreed position fails its future,
     * which fails the surrounding checkpoint without storing: the safe outcome.
     *
     * @param reported the latest position reported by each participant
     * @return the single position every participant has durably reached (or {@code null} if none reported one)
     */
    private CompletableFuture<TrackingToken> reconcile(Map<Checkpointing, TrackingToken> reported) {
        TrackingToken agreed = TrackingTokenUtils.upperBound(reported.values());
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
        return participant.onCheckpointAdvanced(context.segment(), target)
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
     * Resolves the {@link TrackingToken#LATEST} sentinel to the segment's {@code lastConsumedToken}: the latest position
     * actually handed to the handler. A component may use {@code LATEST} (via the {@link CheckpointTrigger} or as a
     * return value) to mean "checkpoint as far as you have given me" without tracking a concrete token; it is
     * deliberately <em>not</em> interpreted as the end of the stream. Any other token is returned unchanged, so the
     * sentinel never reaches the token algebra or the {@link TokenStore}.
     */
    private @Nullable TrackingToken resolveLatest(@Nullable TrackingToken token) {
        return TrackingToken.LATEST.equals(token) ? context.lastConsumedToken() : token;
    }

    /**
     * Invokes {@code request} on every participant concurrently, returning their reported tokens keyed by participant.
     */
    private CompletableFuture<Map<Checkpointing, TrackingToken>> requestEach(
            Function<Checkpointing, CompletableFuture<TrackingToken>> request
    ) {
        Map<Checkpointing, CompletableFuture<TrackingToken>> futures = new LinkedHashMap<>();
        participants.forEach(participant -> futures.put(participant, request.apply(participant)));
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                                .thenApply(ignored -> {
                                    Map<Checkpointing, TrackingToken> reported = new LinkedHashMap<>();
                                    futures.forEach((participant, future) -> reported.put(participant, future.join()));
                                    return reported;
                                });
    }
}
