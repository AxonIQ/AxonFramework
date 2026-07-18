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

package org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;

import java.util.concurrent.CompletableFuture;

import static org.axonframework.common.FutureUtils.emptyCompletedFuture;

/**
 * Decides how a single {@link Segment}'s progress is persisted as a work package processes events. One instance exists
 * per work package (per segment); the work package invokes it around every batch and on claim/release, while the
 * {@link SegmentProgressContext} performs the actual store.
 * <p>
 * The default {@link TokenStoringProgressStrategy} persists the batch-end token every batch (the verbatim behaviour of a
 * processor without advanced progress handling). A strategy may instead defer persistence and advance the stored token
 * only on its own signal (for example, self-checkpointing), reconciling several participants to a single safe position
 * before persisting.
 * <p>
 * <b>Internal API.</b> This interface is marked {@link Internal}: it is the extension point through which advanced (and
 * out-of-module) progress handling plugs into the pooled streaming event processor, not stable end-user API, and its
 * shape may change in a minor or patch release.
 *
 * @author Allard Buijze
 * @see SegmentProgressContext
 * @see TokenStoringProgressStrategy
 * @since 5.3.0
 */
@Internal
public interface SegmentProgressStrategy {

    /**
     * Contributes per-batch resources to {@code context} before the batch is handled, for example a handle a handler may
     * use to influence persistence (such as a checkpoint trigger). Invoked only for non-empty batches, before
     * invocation. Defaults to a no-op.
     *
     * @param context the processing context of the batch about to be handled
     */
    default void contributeBatchResources(ProcessingContext context) {
        // No-op by default; a strategy that exposes a per-batch handle overrides this.
    }

    /**
     * Decides the safe {@link TrackingToken} for the current cycle and persists it through
     * {@link SegmentProgressContext#persistProgress(TrackingToken, ProcessingContext)}, within {@code context}. Invoked
     * on the commit of a non-empty batch (so the store commits atomically with the batch), and on an idle cycle to store
     * progress that advanced without a handled batch. May store nothing (for example, when nothing advanced or no
     * persistence is due yet).
     *
     * @param context the processing context whose transaction the store participates in
     * @return a {@link CompletableFuture} completing when the cycle's persistence (if any) has been applied
     */
    CompletableFuture<Void> onBatchCommit(ProcessingContext context);

    /**
     * Indicates whether the strategy has out-of-band work that requires a worker cycle even when no events are queued
     * (for example, an asynchronous checkpoint request recorded between cycles). Drives the post-cycle reschedule and
     * lets an idle segment run a commit cycle. Strategies that act only within the work package's batches never schedule
     * out-of-band and return {@code false}; this is deliberately <em>not</em> a throttle for idle catch-up stores, which
     * the work package drives on its own claim-extension beat.
     *
     * @return {@code true} if a worker cycle is needed for out-of-band work, {@code false} otherwise
     */
    boolean hasPendingWork();

    /**
     * Invoked when the segment is claimed, before any events are handled. Defaults to a no-op.
     */
    default void onSegmentClaimed() {
        // No-op by default; a strategy that must act at claim time overrides this.
    }

    /**
     * Performs the final progress persistence as the segment is released: decides the safe token and persists it through
     * {@link SegmentProgressContext#persistProgress(TrackingToken, ProcessingContext)} within {@code context}, while the
     * token-store claim is still held. Defaults to a no-op (the per-batch persistence already covered progress).
     *
     * @param context the processing context whose transaction the final store participates in
     * @return a {@link CompletableFuture} completing when the final persistence (if any) has been applied
     */
    default CompletableFuture<Void> onSegmentReleased(ProcessingContext context) {
        return emptyCompletedFuture();
    }

    /**
     * Invoked when the work package aborts, so the strategy can deactivate any out-of-band signal (for example, render a
     * checkpoint trigger inert so a late request becomes a no-op). Defaults to a no-op.
     */
    default void onAbort() {
        // No-op by default; a strategy with an out-of-band trigger overrides this.
    }
}
