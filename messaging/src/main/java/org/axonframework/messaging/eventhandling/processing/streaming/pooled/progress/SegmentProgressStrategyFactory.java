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

/**
 * Creates a {@link SegmentProgressStrategy} for a single work package, binding it to that package's
 * {@link SegmentProgressContext}. One factory is selected per processor and applied to every work package the processor
 * spawns, so all segments of a processor share the same progress-handling behaviour.
 * <p>
 * The default {@link #tokenStoring()} factory produces a {@link TokenStoringProgressStrategy}. A processor with advanced
 * progress handling (such as self-checkpointing) supplies a different factory.
 * <p>
 * <b>Internal API.</b> This interface is marked {@link Internal}: it is part of the progress-persistence seam, consumed
 * by advanced (and out-of-module) progress handling rather than by end users, and its shape may change in a minor or
 * patch release.
 *
 * @author Allard Buijze
 * @see SegmentProgressStrategy
 * @since 5.3.0
 */
@Internal
@FunctionalInterface
public interface SegmentProgressStrategyFactory {

    /**
     * Creates the {@link SegmentProgressStrategy} for the work package owning the given {@code context}.
     * <p>
     * The {@code context} is bound to a single {@link SegmentProgressContext#segment() segment}; the returned strategy
     * should retain it to observe and persist that segment's progress.
     *
     * @param context the work package's progress context to bind the strategy to
     * @return a strategy bound to {@code context}
     */
    SegmentProgressStrategy create(SegmentProgressContext context);

    /**
     * Returns the default factory, producing a {@link TokenStoringProgressStrategy} that persists the batch-end token
     * every batch.
     *
     * @return a factory producing the default token-storing strategy
     */
    static SegmentProgressStrategyFactory tokenStoring() {
        return TokenStoringProgressStrategy::new;
    }
}
