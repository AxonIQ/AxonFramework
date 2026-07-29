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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.beans.ConstructorProperties;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Special Wrapped Token implementation that keeps track of two separate tokens, of which the streams have been merged
 * into a single one. This token keeps track of the progress of the two original "halves", by advancing each
 * individually, until both halves represent the same position.
 * <p>
 * Because the halves are rarely at the same position when they are merged, {@link #position()} reports the
 * <em>lower</em> of the two. The merged segment consequently resumes behind the further-ahead half, and
 * {@link #covers(TrackingToken)} reports {@code false} for every position in between, so the events the further-ahead
 * half already handled are handled a second time. This is deliberate: reporting the higher position would skip the
 * events the further-behind half never reached, losing them. Handlers must therefore be idempotent across a merge.
 * Once the further-behind half catches up, both halves hold the same position, the wrapper collapses into a single
 * token, and the redelivery ends.
 *
 * @author Allard Buijze
 * @since 4.1
 */
public class MergedTrackingToken implements WrappedToken {

    private final TrackingToken lowerSegmentToken;
    private final TrackingToken upperSegmentToken;

    private final transient boolean lowerSegmentAdvanced;
    private final transient boolean upperSegmentAdvanced;

    /**
     * Initialize a Merged Token, with the {@code lowerSegmentToken} representing the progress of the segment with the
     * lower segmentId, and {@code upperSegmentToken} representing the progress of the segment with the higher
     * segmentId.
     *
     * @param lowerSegmentToken the token of the half with the lower segment ID
     * @param upperSegmentToken the token of the half with the higher segment ID
     */
    @JsonCreator
    @ConstructorProperties({"lowerSegmentToken", "upperSegmentToken"})
    public MergedTrackingToken(@JsonProperty("lowerSegmentToken") TrackingToken lowerSegmentToken,
                               @JsonProperty("upperSegmentToken") TrackingToken upperSegmentToken) {
        this(lowerSegmentToken, upperSegmentToken, false, false);
    }

    /**
     * Create a merged token using the given {@code lowerSegmentToken} and {@code upperSegmentToken}.
     *
     * @param lowerSegmentToken the token of the half with the lower segment ID
     * @param upperSegmentToken the token of the half with the higher segment ID
     * @return a token representing the position of the merger of both tokens
     */
    public static TrackingToken merged(TrackingToken lowerSegmentToken, TrackingToken upperSegmentToken) {
        return Objects.equals(lowerSegmentToken, upperSegmentToken)
                ? lowerSegmentToken
                : new MergedTrackingToken(lowerSegmentToken, upperSegmentToken);
    }

    /**
     * Initialize a Merged Token, with the {@code lowerSegmentToken} representing the progress of the segment with the
     * lower segmentId, and {@code upperSegmentToken} representing the progress of the segment with the higher
     * segmentId, additionally indicating if either of these segments were advanced by the latest call to {@link
     * #advancedTo(TrackingToken)}
     *
     * @param lowerSegmentToken    the token of the half with the lower segment ID
     * @param upperSegmentToken    the token of the half with the higher segment ID
     * @param lowerSegmentAdvanced whether the lower segment advanced in the last call
     * @param upperSegmentAdvanced whether the upper segment advanced in the last call
     */
    protected MergedTrackingToken(TrackingToken lowerSegmentToken,
                                  TrackingToken upperSegmentToken,
                                  boolean lowerSegmentAdvanced,
                                  boolean upperSegmentAdvanced) {
        this.lowerSegmentToken = lowerSegmentToken;
        this.upperSegmentToken = upperSegmentToken;
        this.lowerSegmentAdvanced = lowerSegmentAdvanced;
        this.upperSegmentAdvanced = upperSegmentAdvanced;
    }

    /**
     * Indicates whether the given {@code trackingToken} represents a token that is part of a merge.
     *
     * @param trackingToken the token to verify
     * @return {@code true} if the token indicates a merge
     */
    public static boolean isMergeInProgress(TrackingToken trackingToken) {
        return WrappedToken.unwrap(trackingToken, MergedTrackingToken.class).isPresent();
    }

    /**
     * Return the estimated relative token position this Segment will have after a merge operation is completed. In case
     * no estimation can be given or no merge in progress, an {@code OptionalLong.empty()} will be returned.
     *
     * @param trackingToken the token containing a {@link MergedTrackingToken}
     * @return the estimated relative position this Segment will reach after a merge operation is complete.
     */
    public static OptionalLong mergePosition(TrackingToken trackingToken) {
        return WrappedToken.unwrap(trackingToken, MergedTrackingToken.class)
                           .map(m -> m.mergePosition())
                           .filter(p -> p != Long.MIN_VALUE)
                           .map(OptionalLong::of)
                           .orElse(OptionalLong.empty());
    }

    private long mergePosition() {
        if (lowerSegmentToken.position().isPresent() && upperSegmentToken.position().isPresent()) {
            return Math.max(mergePosition(lowerSegmentToken).orElse(lowerSegmentToken.position().getAsLong()),
                            mergePosition(upperSegmentToken).orElse(upperSegmentToken.position().getAsLong()));
        }
        return Long.MIN_VALUE;
    }

    @Override
    public TrackingToken lowerBound(TrackingToken other) {
        TrackingToken newLowerSegmentToken = lowerSegmentToken == null ? null : lowerSegmentToken.lowerBound(other);
        TrackingToken newUpperSegmentToken = upperSegmentToken == null ? null : upperSegmentToken.lowerBound(other);
        if (Objects.equals(newLowerSegmentToken, newUpperSegmentToken)) {
            return newLowerSegmentToken;
        }
        return new MergedTrackingToken(newLowerSegmentToken, newUpperSegmentToken);
    }

    @Override
    public OptionalLong position() {
        if (lowerSegmentToken.position().isPresent() && upperSegmentToken.position().isPresent()) {
            return OptionalLong.of(Math.min(
                    lowerSegmentToken.position().getAsLong(), upperSegmentToken.position().getAsLong()
            ));
        }
        return OptionalLong.empty();
    }

    @Override
    public TrackingToken upperBound(TrackingToken other) {
        TrackingToken newLowerSegmentToken = doAdvance(lowerSegmentToken, other);
        TrackingToken newUpperSegmentToken = doAdvance(upperSegmentToken, other);
        if (Objects.equals(newLowerSegmentToken, newUpperSegmentToken)) {
            return newLowerSegmentToken;
        }
        return new MergedTrackingToken(newLowerSegmentToken, newUpperSegmentToken);
    }

    @Override
    public boolean covers(TrackingToken other) {
        if (lowerSegmentToken == null || upperSegmentToken == null) {
            return other == null;
        }
        return lowerSegmentToken.covers(other) && upperSegmentToken.covers(other);
    }

    @Override
    public boolean samePositionAs(TrackingToken other) {
        if (lowerSegmentToken == null || upperSegmentToken == null) {
            return other == null;
        }
        return lowerSegmentToken.samePositionAs(other) && upperSegmentToken.samePositionAs(other);
    }

    @Override
    public TrackingToken advancedTo(TrackingToken newToken) {
        TrackingToken newLowerSegmentToken = doAdvance(lowerSegmentToken, newToken);
        TrackingToken newUpperSegmentToken = doAdvance(upperSegmentToken, newToken);
        boolean lowerSegmentAdvanced = !Objects.equals(newLowerSegmentToken, lowerSegmentToken);
        boolean upperSegmentAdvanced = !Objects.equals(newUpperSegmentToken, upperSegmentToken);
        if (lowerSegmentAdvanced && upperSegmentAdvanced
                && Objects.equals(newLowerSegmentToken, newUpperSegmentToken)) {
            return newLowerSegmentToken;
        }
        return new MergedTrackingToken(newLowerSegmentToken, newUpperSegmentToken,
                                       lowerSegmentAdvanced, upperSegmentAdvanced);
    }

    @Override
    public <R extends TrackingToken> Optional<R> unwrap(Class<R> tokenType) {
        if (tokenType.isInstance(this)) {
            return Optional.of(tokenType.cast(this));
        } else {
            Optional<R> unwrappedLower = WrappedToken.unwrap(lowerSegmentToken, tokenType);
            Optional<R> unwrappedUpper = WrappedToken.unwrap(upperSegmentToken, tokenType);

            if (lowerSegmentAdvanced && unwrappedLower.isPresent()) {
                return unwrappedLower;
            } else {
                if (upperSegmentAdvanced && unwrappedUpper.isPresent()) {
                    return unwrappedUpper;
                } else {
                    // either will do
                    if (unwrappedLower.isPresent()) {
                        return unwrappedLower;
                    }
                    return unwrappedUpper;
                }
            }
        }
    }

    private TrackingToken doAdvance(TrackingToken currentToken, TrackingToken newToken) {
        if (currentToken == null) {
            return newToken;
        } else if (currentToken instanceof WrappedToken) {
            if (currentToken.covers(newToken)) {
                // this segment is still way ahead.
                return currentToken;
            } else {
                return ((WrappedToken) currentToken).advancedTo(newToken);
            }
        }
        return currentToken.upperBound(newToken);
    }

    @Override
    public TrackingToken lowerBound() {
        TrackingToken lower = WrappedToken.unwrapLowerBound(lowerSegmentToken);
        TrackingToken upper = WrappedToken.unwrapLowerBound(upperSegmentToken);

        return lower == null || upper == null ? null : lower.lowerBound(upper);
    }

    @Override
    public TrackingToken upperBound() {
        TrackingToken lower = WrappedToken.unwrapUpperBound(lowerSegmentToken);
        TrackingToken upper = WrappedToken.unwrapUpperBound(upperSegmentToken);

        return lower == null || upper == null ? null : lower.upperBound(upper);
    }

    /**
     * Returns the token indicating the progress of the lower half (the half with the lower segmentId) of the merged
     * segment represented by this token
     *
     * @return the token indicating the progress of the lower half of the merged segment
     */
    @JsonGetter("lowerSegmentToken")
    @JsonTypeInfo(use = JsonTypeInfo.Id.MINIMAL_CLASS)
    public TrackingToken lowerSegmentToken() {
        return lowerSegmentToken;
    }

    /**
     * Returns the token indicating the progress of the upper half (the half with the higher segmentId) of the merged
     * segment represented by this token
     *
     * @return the token indicating the progress of the upper half of the merged segment
     */
    @JsonGetter("upperSegmentToken")
    @JsonTypeInfo(use = JsonTypeInfo.Id.MINIMAL_CLASS)
    public TrackingToken upperSegmentToken() {
        return upperSegmentToken;
    }

    /**
     * Indicates whether the last call to {@link #advancedTo(TrackingToken)} caused the lower segment to advance
     *
     * @return true if the last advancement moved the lower segment
     */
    @JsonIgnore
    public boolean isLowerSegmentAdvanced() {
        return lowerSegmentAdvanced;
    }

    /**
     * Indicates whether the last call to {@link #advancedTo(TrackingToken)} caused the upper segment to advance
     *
     * @return true if the last advancement moved the upper segment
     */
    @JsonIgnore
    public boolean isUpperSegmentAdvanced() {
        return upperSegmentAdvanced;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MergedTrackingToken that = (MergedTrackingToken) o;
        return Objects.equals(lowerSegmentToken, that.lowerSegmentToken) &&
                Objects.equals(upperSegmentToken, that.upperSegmentToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lowerSegmentToken, upperSegmentToken);
    }

    @Override
    public String toString() {
        return "MergedTrackingToken{" +
                "lowerSegmentToken=" + lowerSegmentToken +
                ", upperSegmentToken=" + upperSegmentToken +
                '}';
    }
}
