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
 * <p>
 * On claim, a listener may return a <em>reset position</em>: a {@link TrackingToken} the segment should be reset
 * to so that events are replayed from that position. The coordinator merges the reset positions returned by the
 * registered listener chain and, if the result is strictly behind the stored tracking token, wraps that token in
 * a {@link org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken ReplayToken} so the
 * segment streams from the reset position instead of the stored position.
 *
 * @author Mateusz Nowak
 * @since 5.1.0
 */
public interface SegmentChangeListener {

    /**
     * Creates a listener that reacts only to claim events. The returned listener requests no reset
     * (it always resolves to {@code null}).
     *
     * @param onClaim asynchronous claim callback
     * @return listener reacting to claim events
     */
    static SegmentChangeListener onClaim(Function<Segment, CompletableFuture<Void>> onClaim) {
        Objects.requireNonNull(onClaim, "Claim listener may not be null");
        return new SimpleSegmentChangeListener(
                segment -> onClaim.apply(segment).thenApply(unused -> null),
                segment -> CompletableFuture.completedFuture(null)
        );
    }

    /**
     * Creates a listener that returns a reset position on claim. The supplied function is invoked on every
     * claim and its returned {@link TrackingToken} (or {@code null}) is merged with reset positions returned
     * by other listeners.
     *
     * @param onClaim asynchronous claim callback returning a reset position or {@code null}
     * @return listener reacting to claim events that requests a reset
     */
    static SegmentChangeListener onClaimWithReset(
            Function<Segment, CompletableFuture<@Nullable TrackingToken>> onClaim
    ) {
        Objects.requireNonNull(onClaim, "Claim listener may not be null");
        return new SimpleSegmentChangeListener(
                onClaim,
                segment -> CompletableFuture.completedFuture(null)
        );
    }

    /**
     * Creates a listener that reacts only to release events.
     *
     * @param onRelease asynchronous release callback
     * @return listener reacting to release events
     */
    static SegmentChangeListener onRelease(Function<Segment, CompletableFuture<Void>> onRelease) {
        Objects.requireNonNull(onRelease, "Release listener may not be null");
        return new SimpleSegmentChangeListener(
                segment -> CompletableFuture.completedFuture(null),
                onRelease
        );
    }

    /**
     * Creates a listener that executes synchronously on claim events. The returned listener requests no
     * reset.
     *
     * @param onClaim synchronous claim callback
     * @return listener reacting to claim events
     */
    static SegmentChangeListener runOnClaim(Consumer<Segment> onClaim) {
        Objects.requireNonNull(onClaim, "Claim listener may not be null");
        return new SimpleSegmentChangeListener(
                segment -> {
                    onClaim.accept(segment);
                    return CompletableFuture.completedFuture(null);
                },
                segment -> CompletableFuture.completedFuture(null)
        );
    }

    /**
     * Creates a listener that executes synchronously on release events.
     *
     * @param onRelease synchronous release callback
     * @return listener reacting to release events
     */
    static SegmentChangeListener runOnRelease(Consumer<Segment> onRelease) {
        Objects.requireNonNull(onRelease, "Release listener may not be null");
        return new SimpleSegmentChangeListener(
                segment -> CompletableFuture.completedFuture(null),
                segment -> {
                    onRelease.accept(segment);
                    return CompletableFuture.completedFuture(null);
                }
        );
    }

    /**
     * Returns a no-op listener.
     *
     * @return no-op segment change listener
     */
    static SegmentChangeListener noOp() {
        return new SimpleSegmentChangeListener(
                segment -> CompletableFuture.completedFuture(null),
                segment -> CompletableFuture.completedFuture(null)
        );
    }

    /**
     * Invoked when a segment has been claimed and processing for that segment is started.
     * <p>
     * The returned {@link TrackingToken} is a <em>reset position</em>: a position the segment should be reset
     * to so that events are replayed from there. The coordinator compares this position against the segment's
     * stored tracking token and, if it is strictly behind, wraps the stored token in a
     * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken ReplayToken} so
     * the segment streams from the reset position.
     * <p>
     * Returning {@code null} requests no reset.
     * <p>
     * On a first-ever claim of a segment (i.e. when the stored token is {@code null}), any returned reset
     * position is ignored; a reset is only meaningful relative to a position the processor has already moved
     * past. The listener is still invoked in that case for any observational side effects it performs.
     *
     * @param segment claimed {@link Segment}
     * @return {@link CompletableFuture} that completes with the reset position for this segment, or
     *         {@code null} if the listener requests no reset
     */
    CompletableFuture<@Nullable TrackingToken> onSegmentClaimed(Segment segment);

    /**
     * Invoked when a segment has been released.
     *
     * @param segment released {@link Segment}
     * @return {@link CompletableFuture} that completes when handling has finished
     */
    CompletableFuture<Void> onSegmentReleased(Segment segment);

    /**
     * Compose this listener with {@code next}, invoking this listener first and the next listener second.
     * <p>
     * Reset positions returned by both listeners are merged via
     * {@link TrackingToken#lowerBound(TrackingToken)} so the resulting listener requests the lowest position
     * required by either of its constituents.
     *
     * @param next listener to invoke after this listener
     * @return composed listener invoking listeners sequentially for claim and release events
     */
    default SegmentChangeListener andThen(SegmentChangeListener next) {
        Objects.requireNonNull(next, "Next listener may not be null");
        return new SimpleSegmentChangeListener(
                segment -> onSegmentClaimed(segment).thenCompose(
                        first -> next.onSegmentClaimed(segment).thenApply(second -> {
                            if (first == null) {
                                return second;
                            }
                            if (second == null) {
                                return first;
                            }
                            return first.lowerBound(second);
                        })
                ),
                segment -> onSegmentReleased(segment).thenCompose(unused -> next.onSegmentReleased(segment))
        );
    }
}
