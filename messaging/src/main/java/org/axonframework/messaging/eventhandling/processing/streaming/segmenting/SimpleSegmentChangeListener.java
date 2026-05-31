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

package org.axonframework.messaging.eventhandling.processing.streaming.segmenting;

import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Simple {@link SegmentChangeListener} implementation backed by claim and release handlers.
 *
 * @author Mateusz Nowak
 * @since 5.1.0
 */
public class SimpleSegmentChangeListener implements SegmentChangeListener {

    private final Function<Segment, CompletableFuture<@Nullable TrackingToken>> onClaim;
    private final Function<Segment, CompletableFuture<Void>> onRelease;

    /**
     * Creates a listener with explicit claim and release handlers.
     *
     * @param onClaim   the claim handler returning a reset position (or {@code null})
     * @param onRelease the release handler
     */
    public SimpleSegmentChangeListener(
            Function<Segment, CompletableFuture<@Nullable TrackingToken>> onClaim,
            Function<Segment, CompletableFuture<Void>> onRelease
    ) {
        this.onClaim = Objects.requireNonNull(onClaim, "Claim listener may not be null");
        this.onRelease = Objects.requireNonNull(onRelease, "Release listener may not be null");
    }

    @Override
    public CompletableFuture<@Nullable TrackingToken> onSegmentClaimed(Segment segment) {
        return onClaim.apply(segment);
    }

    @Override
    public CompletableFuture<Void> onSegmentReleased(Segment segment) {
        return onRelease.apply(segment);
    }
}
