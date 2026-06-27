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

package org.axonframework.messaging.eventhandling.processing.streaming.token;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Objects;

/**
 * Utility methods to combine collections of {@link TrackingToken TrackingTokens}.
 * <p>
 * Where {@link TrackingToken#lowerBound(TrackingToken)} and {@link TrackingToken#upperBound(TrackingToken)} combine two
 * tokens, these helpers reduce an arbitrary collection to a single token, ignoring {@code null} elements.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
public abstract class TrackingTokenUtils {

    private TrackingTokenUtils() {
    }

    /**
     * Reduces the given {@code tokens} to their {@link TrackingToken#lowerBound(TrackingToken) lowerBound}, ignoring any
     * {@code null} elements.
     *
     * @param tokens the tokens to combine
     * @return the lower bound of the non-{@code null} tokens, or {@code null} if there are none
     */
    @Nullable
    public static TrackingToken lowerBound(Collection<TrackingToken> tokens) {
        return tokens.stream()
                     .filter(Objects::nonNull)
                     .reduce(TrackingToken::lowerBound)
                     .orElse(null);
    }

    /**
     * Reduces the given {@code tokens} to their {@link TrackingToken#upperBound(TrackingToken) upperBound}, ignoring any
     * {@code null} elements.
     *
     * @param tokens the tokens to combine
     * @return the upper bound of the non-{@code null} tokens, or {@code null} if there are none
     */
    @Nullable
    public static TrackingToken upperBound(Collection<TrackingToken> tokens) {
        return tokens.stream()
                     .filter(Objects::nonNull)
                     .reduce(TrackingToken::upperBound)
                     .orElse(null);
    }

    /**
     * Indicates whether {@code candidate} represents a position at or beyond {@code reference}, comparing the raw
     * {@link WrappedToken#unwrapUpperBound(TrackingToken) unwrapped} upper-bound positions rather than the tokens
     * directly.
     * <p>
     * Unwrapping keeps the comparison robust when a replay concludes: {@code reference} may still be a
     * {@link ReplayToken} while {@code candidate} is the plain stream token it advances to, and a direct
     * {@code candidate.covers(reference)} would then either throw (the raw token type rejecting the wrapped one) or
     * report a false regression. Comparing the unwrapped positions reports that transition as an advance, while still
     * returning {@code false} for a genuine regression or an incomparable token (such as a partially-regressed
     * multi-source token).
     *
     * @param candidate the token whose position is tested
     * @param reference the token to compare against
     * @return {@code true} if {@code candidate} covers {@code reference} once both are unwrapped to their raw
     * upper-bound positions
     */
    public static boolean coversWhenUnwrapped(TrackingToken candidate, TrackingToken reference) {
        return WrappedToken.unwrapUpperBound(candidate).covers(WrappedToken.unwrapUpperBound(reference));
    }
}
