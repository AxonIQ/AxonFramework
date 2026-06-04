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

import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;

import java.util.Optional;

/**
 * Handle through which a {@link Checkpointing} unit tells the owning processor how far it is safe to advance the stored
 * {@link TrackingToken} for a single claimed {@link Segment}. Handed to the unit on
 * {@link Checkpointing#onSegmentClaimed(Segment, CheckpointTrigger)} and valid only for the duration of that claim.
 * <p>
 * Requesting never blocks: it wakes the segment's worker, which runs the checkpoint on the processing thread (asking
 * every self-checkpointing component to cover the requested position and storing the single position they reconcile to
 * -- the highest any component reported, with laggards driven to also reach it). A checkpoint request is segment-scoped:
 * it applies to <em>all</em> of the processor's components on that segment.
 * <p>
 * <b>Lifecycle.</b> The trigger is valid only for the duration of the claim. Once the segment is released (after
 * {@link Checkpointing#onSegmentReleased(Segment, TrackingToken)}), the trigger is permanently <em>inert</em>:
 * subsequent requests are <b>silently ignored</b> -- they neither schedule the worker nor advance any stored token.
 * After release the worker no longer holds the claim and there is no safe point to record (the segment may already be
 * owned by another node); a late async-write acknowledgement must therefore be a no-op rather than an error. The final
 * safe token for the claim comes solely from the return value of
 * {@link Checkpointing#onSegmentReleased(Segment, TrackingToken)}.
 *
 * @author Allard Buijze
 * @see Checkpointing
 * @since 5.2.0
 */
public interface CheckpointTrigger {

    /**
     * The {@link Context.ResourceKey} under which the per-segment {@code CheckpointTrigger} is made available in the
     * active {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} during event handling. The owning
     * processor places it there so a handler method can declare a {@code CheckpointTrigger} parameter and request
     * checkpoints directly.
     */
    Context.ResourceKey<CheckpointTrigger> RESOURCE_KEY = Context.ResourceKey.withLabel("checkpointTrigger");

    /**
     * Returns an {@link Optional} of the {@code CheckpointTrigger} keyed under {@link #RESOURCE_KEY} in the given
     * {@code context}, or {@link Optional#empty()} if none is present (for example, in a processor that does not
     * checkpoint).
     *
     * @param context The context to retrieve the {@code CheckpointTrigger} from.
     * @return An {@link Optional} holding the {@code CheckpointTrigger} keyed under {@link #RESOURCE_KEY}, if present.
     */
    static Optional<CheckpointTrigger> fromContext(Context context) {
        return Optional.ofNullable(context.getResource(RESOURCE_KEY));
    }

    /**
     * Requests a checkpoint covering everything handed to the handler so far (the segment's current
     * {@code lastConsumedToken}).
     */
    void requestCheckpoint();

    /**
     * Requests a checkpoint that must cover at least {@code token} -- the component declares it is safe up to
     * {@code token}. Concurrent requests combine via {@link TrackingToken#upperBound(TrackingToken)}, so the requested
     * position is the highest anyone asked for and only rises.
     * <p>
     * Passing {@link TrackingToken#LATEST} declares the component safe up to the latest position handed to the handler
     * so far; the owning processor resolves it to that position (it is <em>not</em> the end of the stream). This is
     * equivalent to {@link #requestCheckpoint()} and is convenient for a component that triggers checkpoints on certain
     * key events without tracking a concrete token.
     *
     * @param token The position the requesting component has made durable, or {@link TrackingToken#LATEST} for the
     *              latest position handed to the handler so far.
     */
    void requestCheckpoint(TrackingToken token);
}
