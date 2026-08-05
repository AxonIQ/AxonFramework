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
 * @since 5.3.0
 */
public abstract class TrackingTokenUtils {

    private TrackingTokenUtils() {
    }

    /**
     * Reduces the given {@code tokens} to their {@link TrackingToken#lowerBound(TrackingToken) lowerBound}, ignoring any
     * {@code null} elements.
     *
     * @param tokens the tokens to combine
     * @return the lower bound of the non-{@code null} tokens, or {@link TrackingToken#FIRST} if there are none
     */
    public static TrackingToken lowerBound(Collection<@Nullable TrackingToken> tokens) {
        return tokens.stream()
                     .filter(Objects::nonNull)
                     .reduce(TrackingToken::lowerBound)
                     .orElse(TrackingToken.FIRST);
    }

    /**
     * Reduces the given {@code tokens} to their {@link TrackingToken#upperBound(TrackingToken) upperBound}, ignoring any
     * {@code null} elements.
     *
     * @param tokens the tokens to combine
     * @return the upper bound of the non-{@code null} tokens, or {@code null} if there are none
     */
    @Nullable
    public static TrackingToken upperBound(Collection<@Nullable TrackingToken> tokens) {
        return tokens.stream()
                     .filter(Objects::nonNull)
                     .reduce(TrackingToken::upperBound)
                     .orElse(null);
    }

    /**
     * Indicates whether {@code candidate} represents a position at or beyond {@code reference}, comparing the raw
     * {@link WrappedToken#unwrapLowerBound(TrackingToken) unwrapped} lower-bound and
     * {@link WrappedToken#unwrapUpperBound(TrackingToken) upper-bound} positions rather than the tokens directly.
     * <p>
     * A wrapped token may describe a range, of which both ends must cover {@code reference} to count as an advance. An
     * unwrapped end that is absent on {@code candidate} is never an advance; absent on {@code reference} it leaves
     * nothing to regress from.
     *
     * @param candidate the token whose position is tested
     * @param reference the token to compare against
     * @return {@code true} if both ends of {@code candidate} cover the matching ends of {@code reference} once
     * unwrapped to their raw positions
     */
    public static boolean coversWhenUnwrapped(TrackingToken candidate, TrackingToken reference) {
        return covers(WrappedToken.unwrapUpperBound(candidate), WrappedToken.unwrapUpperBound(reference))
                && covers(WrappedToken.unwrapLowerBound(candidate), WrappedToken.unwrapLowerBound(reference));
    }

    private static boolean covers(@Nullable TrackingToken candidate, @Nullable TrackingToken reference) {
        if (reference == null) {
            return true;
        }
        return candidate != null && candidate.covers(reference);
    }
}
