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
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;

import java.util.concurrent.CompletableFuture;

/**
 * Declares that an event-handling unit manages its own progress: it decides when its work for a {@link Segment} is
 * durable and asks the processor to advance the stored {@link TrackingToken} through a {@link CheckpointTrigger}.
 * Implement this when handling writes asynchronously, or triggers work that is not durable by the time the event
 * handler returns.
 * <p>
 * Two kinds of unit implement it: an annotated handler POJO (whose wrapping
 * {@link org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent} detects it and exposes
 * it via {@link EventHandlingComponent#unwrap(Class)}), and a programmatic component (which implements both
 * {@link EventHandlingComponent} and this interface). Either way the processor finds it via
 * {@code unwrap(Checkpointing.class)} and runs in request-driven mode; a processor whose every handler is
 * {@code Checkpointing} is fully-deferred (it advances the stored token only on explicit request), whereas a processor
 * with at least one ordinary handler runs in auto mode (it requests a checkpoint at the batch-end token every batch).
 * <p>
 * Only a streaming processor (with a tracking token and segments) can honour this protocol. A processor that does not
 * stream -- such as a
 * {@link org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessor} -- simply never
 * invokes the lifecycle callbacks (no {@link #onSegmentClaimed(Segment, CheckpointTrigger)}, no checkpoints) and does
 * not expose a {@link CheckpointTrigger}, so the checkpointing behaviour is inert there.
 * <p>
 * A checkpointing unit may use one of two styles:
 * <ul>
 *     <li><b>confirm-then-store</b>: only call {@link CheckpointTrigger#requestCheckpoint(TrackingToken)} once its
 *     async write to that position has confirmed durable; {@link #onCheckpointAdvanced(Segment, TrackingToken)} then
 *     just clears buffers and returns its current high-water mark.</li>
 *     <li><b>decide-then-flush</b>: call {@link CheckpointTrigger#requestCheckpoint(TrackingToken)} optimistically,
 *     then perform the blocking flush inside {@link #onCheckpointAdvanced(Segment, TrackingToken)}, returning the token
 *     it actually reached once durable.</li>
 * </ul>
 * Both are valid because {@link #onCheckpointAdvanced(Segment, TrackingToken)} returns a future (carrying the reached
 * token) the processor awaits before storing.
 * <p>
 * Orthogonal to <em>when</em> the request is made is the <em>kind of workload</em> the future represents. Two patterns
 * are common, and both rely on the same future-based contract:
 * <ul>
 *     <li><b>In-memory projection with asynchronous persistence</b>: the unit applies each event to in-memory state on
 *     the processing thread -- so ordering is naturally preserved -- and only persists asynchronously. When a checkpoint
 *     is taken it snapshots that state to durable storage and completes the future once the snapshot is durable,
 *     reporting the snapshotted position. Event handling resumes as soon as the future completes; the future merely gates
 *     storing the token on the snapshot becoming durable.</li>
 *     <li><b>Truly asynchronous handlers</b>: the unit dispatches work that runs independently of event handling and does
 *     <em>not</em> block the next event, so it may fall behind the stream. Ordering across that work is then the unit's
 *     own responsibility, but it has full control over it. At checkpoint time the unit returns a future that completes
 *     only once the in-flight work has caught up far enough to cover the requested position. Because the processor awaits
 *     that future before resuming the segment's worker, the checkpoint effectively becomes a barrier: event handling is
 *     "blocked" until the asynchronous work has reached the checkpoint.</li>
 * </ul>
 * <p>
 * <b>The processor awaits the returned future without a timeout</b>: it stores the checkpoint (and, on release, frees
 * the claim) only once the future completes. A future that never completes therefore stalls progress for that segment
 * indefinitely. The framework deliberately does not impose a timeout -- a unit that confirms durability out of band may
 * legitimately take a long time. If a unit's asynchronous work could hang, it should bound its <em>own</em> future, for
 * example:
 * <pre>{@code
 * public CompletableFuture<TrackingToken> onCheckpointAdvanced(Segment segment, TrackingToken requested) {
 *     return flushToStore(requested)            // returns CompletableFuture<TrackingToken>
 *             .orTimeout(30, TimeUnit.SECONDS);  // fails the checkpoint instead of stalling the segment forever
 * }
 * }</pre>
 * A future completed exceptionally (whether from such a timeout or any other failure) fails the checkpoint without
 * storing, leaving the stored token where it is; the segment retries on the next cycle.
 * <p>
 * <b>Transactional coupling within a batch.</b> The checkpoint is taken on the commit of the <em>same</em> transaction
 * that processed the batch: the processor awaits the returned future while that transaction is still open. Two
 * consequences follow. First, a slow or never-completing future does not merely stall progress -- it holds the batch's
 * transaction open for the duration of the await. Second, if the checkpoint fails (the future completes exceptionally),
 * the batch transaction rolls back, undoing the work of <em>every</em> handler in that batch, including ordinary
 * (non-checkpointing) handlers and other checkpointing components sharing the segment. A misbehaving checkpointing
 * component can therefore affect co-located handlers. For this reason, <b>a checkpointing component is best isolated in
 * its own pooled streaming event processor</b> (one whose every handler is {@code Checkpointing}, so it runs
 * fully-deferred) rather than mixed with ordinary handlers, both to avoid the rollback coupling and because in a mixed
 * processor auto checkpointing wins and the component cannot actually defer its segment's token.
 * <p>
 * The <b>only method that must be implemented</b> is {@link #onCheckpointAdvanced(Segment, TrackingToken)};
 * {@link #onSegmentClaimed(Segment, CheckpointTrigger)} and {@link #onSegmentReleased(Segment, TrackingToken)} have
 * sensible defaults (see each method).
 * <p>
 * <b>Internal API.</b> This interface is marked {@link Internal}: self-checkpointing is currently intended primarily
 * for internal and advanced use, is not part of the documented public feature set, and its shape may change in a minor
 * or patch release.
 *
 * @author Allard Buijze
 * @see CheckpointTrigger
 * @since 5.2.0
 */
@Internal
public interface Checkpointing {

    /**
     * Invoked when {@code segment} is claimed, handing the unit the {@link CheckpointTrigger} it uses to declare safe
     * positions for that segment. Retain it keyed by segment; it is invalid after
     * {@link #onSegmentReleased(Segment, TrackingToken)}.
     * <p>
     * Defaults to a no-op: a unit that obtains its trigger another way -- typically through a {@link CheckpointTrigger}
     * handler-method parameter -- does not need to retain it here.
     * <p>
     * Invoked synchronously as part of claiming the segment. Throwing from this method fails the claim cycle: the
     * segment is not claimed and the coordinator retries it (after a back-off). This is deliberate -- a component that
     * never received its trigger could never checkpoint -- so signal a genuine inability to accept the claim by
     * throwing, but do not throw for transient conditions that a retained trigger would handle later.
     *
     * @param segment The segment that was claimed.
     * @param trigger The handle to request checkpoints for {@code segment}.
     */
    default void onSegmentClaimed(Segment segment, CheckpointTrigger trigger) {
        // No-op by default; override only if the trigger must be retained at claim time.
    }

    /**
     * Invoked on the processing thread when the processor takes a checkpoint for {@code segment} that must cover
     * {@code requested}. Ensure work is durable at least up to {@code requested}, discard buffered state, then return
     * the highest token now safe -- which may exceed {@code requested} if more async work has drained. The processor
     * stores the reported token only after every returned future completes. When several checkpointing units share a
     * segment, it does not simply store the lowest report: it reconciles their positions to a single agreed token (the
     * highest any unit reported), re-requesting any unit that has not yet reached it, and stores that -- so no unit is
     * ever left durably ahead of the stored token.
     * <p>
     * Contract: the returned token must {@link TrackingToken#covers(TrackingToken) cover} {@code requested}. The
     * processor only ever <em>advances</em> the stored token: a value at or behind the last stored checkpoint is ignored
     * (never rewinds progress).
     * <p>
     * <b>Exceptional completion.</b> A unit that cannot reach {@code requested} must complete its future exceptionally
     * (returning a token that does not cover {@code requested} is treated the same way). The checkpoint then fails and
     * nothing is stored. The processor handles this as a processing error: it aborts the segment's worker and releases
     * the segment, so the coordinator re-claims it (after a back-off) and resumes from the <em>last stored
     * checkpoint</em> -- the events since are redelivered. The stored token therefore never advances on a failed
     * checkpoint, and no progress is lost; an isolated failure costs a segment re-claim and some redelivery.
     * <p>
     * The returned future is the lever for the two workloads described on this type. For an
     * <b>in-memory projection with asynchronous persistence</b>, complete the future once the snapshot of the in-memory
     * state has become durable, reporting the snapshotted position; the processing thread then resumes immediately. For
     * a <b>truly asynchronous handler</b>, complete the future only once the in-flight work has caught up far enough to
     * cover {@code requested} -- the processor awaits it before resuming the segment's worker, so the checkpoint acts as
     * a barrier that holds event handling until the asynchronous work has reached this position.
     *
     * @param segment   The segment being checkpointed.
     * @param requested The segment-scoped position this checkpoint must cover (the highest anyone requested, or the
     *                  batch-end token in auto mode).
     * @return The highest token this unit is durably safe at; must cover {@code requested} and must not sit behind the
     * last persisted checkpoint.
     */
    CompletableFuture<TrackingToken> onCheckpointAdvanced(Segment segment, TrackingToken requested);

    /**
     * Invoked when {@code segment} is being released. Flush everything handled so far, discard the segment's local
     * state, and report the highest token made durable; the processor reconciles it with any co-located units (the same
     * way as {@link #onCheckpointAdvanced(Segment, TrackingToken)}) and stores the result while still holding the claim,
     * then releases the claim. After this the {@link CheckpointTrigger} for the segment is invalid.
     * <p>
     * The returned token must lie in the range {@code [lastStoredCheckpoint, upTo]}: it must not rewind below the last
     * persisted checkpoint (a regressive value is ignored), and it cannot exceed {@code upTo} (a unit can only be
     * durable up to what it was handed). Returning a token <em>below</em> {@code upTo} is the normal lagging case: the
     * uncovered tail (from the returned token up to {@code upTo}) is simply reprocessed on the next claim. Forcing the
     * value all the way up to {@code upTo} is not required and is often impossible for asynchronous work.
     * <p>
     * The same two workloads described on this type apply here. An <b>in-memory projection with
     * asynchronous persistence</b> takes a final snapshot and completes the future once it is durable, normally
     * reporting {@code upTo}. A <b>truly asynchronous handler</b> completes the future once its in-flight work has
     * drained as far as it can before the claim is given up, reporting whatever position is durable by then -- typically
     * lower than {@code upTo}, with the uncovered tail reprocessed on the next claim. Unlike
     * {@link #onCheckpointAdvanced(Segment, TrackingToken) onCheckpointAdvanced}, reaching {@code upTo} is not required
     * here: there is no later request to satisfy, so an asynchronous unit may report best-effort progress rather than
     * forcing a full drain.
     * <p>
     * <b>Exceptional completion.</b> Unlike {@link #onCheckpointAdvanced(Segment, TrackingToken)}, a future that
     * completes exceptionally here does <em>not</em> abort or retry -- the segment is being given up regardless. The
     * release still proceeds: the checkpoint simply does not advance for what the unit could not confirm, the claim is
     * freed, and the uncovered tail (everything past the last stored checkpoint) is reprocessed when the segment is next
     * claimed. There is nothing the framework can do about a genuine durability failure at release beyond this
     * reprocessing, so a unit that knows a lower already-durable position should <em>report</em> it (return it) rather
     * than fail, to keep the redelivered window as small as possible.
     * <p>
     * Defaults to {@link #onCheckpointAdvanced(Segment, TrackingToken) onCheckpointAdvanced(segment, upTo)} -- i.e. a
     * full flush up to the consumed position. Because that delegate must {@link TrackingToken#covers(TrackingToken) cover}
     * its argument or complete exceptionally, the default does not advance the checkpoint on release if the unit cannot
     * reach {@code upTo} (the claim is still released, per the exceptional-completion behaviour above); override this
     * method to instead report a lower already-durable position (the relaxed, best-effort semantics) for an asynchronous
     * unit that may legitimately lag behind {@code upTo} at release.
     *
     * @param segment The segment being released.
     * @param upTo    The segment's {@code lastConsumedToken}: the position to drain toward.
     * @return The highest token durably persisted; at least the last persisted checkpoint and at most {@code upTo}, and
     * typically lower than {@code upTo} when asynchronous work is still in flight.
     */
    default CompletableFuture<TrackingToken> onSegmentReleased(Segment segment, TrackingToken upTo) {
        return onCheckpointAdvanced(segment, upTo);
    }
}
