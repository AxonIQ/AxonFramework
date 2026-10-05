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
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Listener invoked when a processor claims or releases a {@link Segment}.
 *
 * @author Mateusz Nowak
 * @since 5.1.0
 */
public interface SegmentChangeListener {

    /**
     * Creates a listener that reacts only to claim events.
     *
     * @param onClaim asynchronous claim callback
     * @return listener reacting to claim events
     */
    static SegmentChangeListener onClaim(Function<Segment, CompletableFuture<Void>> onClaim) {
        return new SimpleSegmentChangeListener((segment, from) -> onClaim.apply(segment),
                                               segment -> CompletableFuture.completedFuture(null));
    }

    /**
     * Creates a listener that reacts only to release events.
     *
     * @param onRelease asynchronous release callback
     * @return listener reacting to release events
     */
    static SegmentChangeListener onRelease(Function<Segment, CompletableFuture<Void>> onRelease) {
        return new SimpleSegmentChangeListener((segment, from) -> CompletableFuture.completedFuture(null),
                                               onRelease);
    }

    /**
     * Creates a listener that executes synchronously on claim events.
     *
     * @param onClaim synchronous claim callback
     * @return listener reacting to claim events
     */
    static SegmentChangeListener runOnClaim(Consumer<Segment> onClaim) {
        Objects.requireNonNull(onClaim, "Claim listener may not be null");
        return new SimpleSegmentChangeListener((segment, from) -> {
            onClaim.accept(segment);
            return CompletableFuture.completedFuture(null);
        }, segment -> CompletableFuture.completedFuture(null));
    }

    /**
     * Creates a listener that executes synchronously on release events.
     *
     * @param onRelease synchronous release callback
     * @return listener reacting to release events
     */
    static SegmentChangeListener runOnRelease(Consumer<Segment> onRelease) {
        Objects.requireNonNull(onRelease, "Release listener may not be null");
        return new SimpleSegmentChangeListener((segment, from) -> CompletableFuture.completedFuture(null),
                                               segment -> {
                                                   onRelease.accept(segment);
                                                   return CompletableFuture.completedFuture(null);
                                               });
    }

    /**
     * Returns a no-op listener.
     *
     * @return no-op segment change listener
     */
    static SegmentChangeListener noOp() {
        return new SimpleSegmentChangeListener(
                (segment, from) -> CompletableFuture.completedFuture(null),
                segment -> CompletableFuture.completedFuture(null)
        );
    }

    /**
     * Invoked when a segment has been claimed and processing for that segment is started.
     * <p>
     * The {@code from} token is the position this segment resumes at, letting a listener see how far along the stream
     * the segment is before the first event is delivered.
     *
     * @param segment claimed {@link Segment}
     * @param from    the {@link TrackingToken} stored for the {@code segment}, or {@code null} when processing starts
     *                at the beginning of the stream
     * @return {@link CompletableFuture} that completes when handling has finished
     */
    CompletableFuture<Void> onSegmentClaimed(Segment segment, @Nullable TrackingToken from);

    /**
     * Invoked when a segment has been released.
     * <p>
     * Invoked while the claim on the {@code segment} is still held, so a listener can wind down the work it runs for
     * that segment before another node can pick it up. The claim is released once the returned
     * {@link CompletableFuture} completes, or once the processor's claim extension threshold has passed, whichever
     * comes first.
     *
     * @param segment released {@link Segment}
     * @return {@link CompletableFuture} that completes when handling has finished
     */
    CompletableFuture<Void> onSegmentReleased(Segment segment);

    /**
     * Compose this listener with {@code next}, invoking this listener first and the next listener second.
     *
     * @param next listener to invoke after this listener
     * @return composed listener invoking listeners sequentially for claim and release events
     */
    default SegmentChangeListener andThen(SegmentChangeListener next) {
        Objects.requireNonNull(next, "Next listener may not be null");
        SegmentChangeListener first = this;
        return new SimpleSegmentChangeListener(
                (segment, from) -> first.onSegmentClaimed(segment, from)
                                        .thenCompose(unused -> next.onSegmentClaimed(segment, from)),
                segment -> first.onSegmentReleased(segment).thenCompose(unused -> next.onSegmentReleased(segment))
        );
    }
}
