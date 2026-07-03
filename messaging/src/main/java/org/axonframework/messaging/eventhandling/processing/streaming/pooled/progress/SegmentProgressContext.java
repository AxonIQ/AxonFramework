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
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * The handle a {@link SegmentProgressStrategy} uses to observe and persist the progress of the single {@link Segment} it
 * is bound to. Implemented by the owning work package and handed to the strategy on
 * {@link SegmentProgressStrategyFactory#create(SegmentProgressContext) creation}.
 * <p>
 * The context owns token persistence: a strategy decides <em>which</em> {@link TrackingToken} is safe and calls
 * {@link #persistProgress(TrackingToken, ProcessingContext)}, which performs the monotonic store and resets the
 * claim-extension deadline. The strategy never touches the
 * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore} directly. This keeps the
 * store-decision (and the claim-extension timing coupled to it) inside the work package, so a strategy carries only the
 * decision logic.
 * <p>
 * <b>Internal API.</b> This interface is marked {@link Internal}: it is the contract between the work package and a
 * progress strategy, consumed by advanced (and out-of-module) strategies rather than by end users, and its shape may
 * change in a minor or patch release.
 *
 * @author Allard Buijze
 * @see SegmentProgressStrategy
 * @since 5.2.0
 */
@Internal
public interface SegmentProgressContext {

    /**
     * Returns the {@link Segment} the owning work package, and therefore this strategy, is responsible for.
     *
     * @return the segment this strategy is bound to
     */
    Segment segment();

    /**
     * Returns the highest {@link TrackingToken} handed to the handler so far for this segment (the work package's
     * {@code lastConsumedToken}), or {@code null} if nothing has been consumed yet.
     * <p>
     * This is the position a strategy treats as "safe up to here" when deciding what to persist.
     *
     * @return the last consumed token, or {@code null} if nothing has been consumed yet
     */
    @Nullable
    TrackingToken lastConsumedToken();

    /**
     * Wakes the segment's worker so a commit cycle runs. The lever a strategy uses to act on out-of-band work (such as
     * an asynchronous checkpoint request) recorded between worker cycles; safe to call from any thread.
     */
    void scheduleWorker();

    /**
     * Persists {@code candidate} as this segment's progress within the given {@code context}, keeping the stored
     * {@link TrackingToken} monotonic. A {@code null}, already-stored, or non-advancing token is ignored; a successful
     * store also resets the claim-extension deadline. The store runs as part of {@code context} so it commits atomically
     * with the surrounding batch (or release) transaction.
     *
     * @param candidate the token the strategy decided is safe, or {@code null} for nothing to persist
     * @param context   the processing context whose transaction the store participates in
     * @return a {@link CompletableFuture} completing when the store (if any) has been applied
     */
    CompletableFuture<Void> persistProgress(@Nullable TrackingToken candidate, ProcessingContext context);
}
