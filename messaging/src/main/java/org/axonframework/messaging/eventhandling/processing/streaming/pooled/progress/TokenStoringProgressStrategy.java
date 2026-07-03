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

import org.axonframework.common.FutureUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;

import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

/**
 * Default {@link SegmentProgressStrategy}: persists the batch-end token ({@link SegmentProgressContext#lastConsumedToken()})
 * on every commit. This is the verbatim behaviour of a pooled streaming event processor without advanced progress
 * handling: the stored {@link TrackingToken} advances to the position consumed by each batch.
 * <p>
 * It schedules no out-of-band work ({@link #hasPendingWork()} is always {@code false}) and acts only within the work
 * package's batch and idle cycles. On an idle cycle the work package still invokes {@link #onBatchCommit(ProcessingContext)}
 * on its claim-extension beat, so a token advanced purely by ignored events is caught up then.
 * <p>
 * <b>Internal API.</b> This class is marked {@link Internal}: it is the default implementation of the progress seam and
 * not stable end-user API.
 *
 * @author Allard Buijze
 * @see SegmentProgressStrategy
 * @since 5.2.0
 */
@Internal
public final class TokenStoringProgressStrategy implements SegmentProgressStrategy {

    private final SegmentProgressContext context;

    /**
     * Constructs a {@code TokenStoringProgressStrategy} bound to the given {@code context}.
     *
     * @param context the work package's progress context to persist progress through
     */
    public TokenStoringProgressStrategy(SegmentProgressContext context) {
        this.context = requireNonNull(context, "The SegmentProgressContext may not be null.");
    }

    @Override
    public CompletableFuture<Void> onBatchCommit(ProcessingContext processingContext) {
        TrackingToken candidate = context.lastConsumedToken();
        return candidate == null
                ? FutureUtils.emptyCompletedFuture()
                : context.persistProgress(candidate, processingContext);
    }

    @Override
    public boolean hasPendingWork() {
        // Acts only within Coordinator-driven batches and the work package's idle beat; never schedules out-of-band.
        return false;
    }
}
