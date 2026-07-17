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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.axonframework.conversion.Converter;
import org.jspecify.annotations.Nullable;

import java.beans.ConstructorProperties;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Token keeping track of the position before a reset was triggered.
 * <p>
 * This allows for downstream components to detect messages that are redelivered as part of a replay.
 *
 * @author Allard Buijze
 * @since 3.2.0
 */
public class ReplayToken implements WrappedToken {

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
    private final TrackingToken tokenAtReset;
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@class")
    private final TrackingToken currentToken;
    private final byte[] resetContext;
    private final transient boolean lastMessageWasReplay;

    /**
     * Initializes a ReplayToken with {@code tokenAtReset} which represents the position at which a reset was triggered
     * and the {@code newRedeliveryToken} which represents current token.
     *
     * @param tokenAtReset       the token representing the position at which the reset was triggered
     * @param newRedeliveryToken the current token
     * @param resetContext       the reset context of the replay
     */
    @JsonCreator
    @ConstructorProperties({"tokenAtReset", "currentToken", "resetContext"})
    ReplayToken(@JsonProperty("tokenAtReset") TrackingToken tokenAtReset,
                @JsonProperty("currentToken") TrackingToken newRedeliveryToken,
                @JsonProperty("resetContext") byte[] resetContext) {
        this(tokenAtReset, newRedeliveryToken, resetContext, true);
    }

    private ReplayToken(
            TrackingToken tokenAtReset,
            TrackingToken newRedeliveryToken,
            byte[] resetContext,
            boolean lastMessageWasReplay
    ) {
        this.tokenAtReset = tokenAtReset;
        this.currentToken = newRedeliveryToken;
        this.resetContext = resetContext;
        this.lastMessageWasReplay = lastMessageWasReplay;
    }

    /**
     * Creates a new TrackingToken that represents the tail of the stream. It will be in replay state until the position
     * of the provided {@code tokenAtReset}. After that, the {@code tokenAtReset} will become the active token and the
     * stream will no longer be considered as replaying.
     *
     * @param tokenAtReset the token present when the reset was triggered
     * @return a token that represents a reset to the tail of the stream
     */
    public static TrackingToken createReplayToken(@Nullable TrackingToken tokenAtReset) {
        return createReplayToken(tokenAtReset, null);
    }

    /**
     * Creates a new TrackingToken that represents the given {@code startPosition} of a stream. It will be in replay
     * state until the position of the provided {@code tokenAtReset}. After that, the {@code tokenAtReset} will become
     * the active token and the stream will no longer be considered as replaying.
     *
     * @param tokenAtReset  the token present when the reset was triggered
     * @param startPosition the position where the token should be reset to and start replaying from
     * @return a token that represents a reset to the {@code startPosition} until the provided {@code tokenAtReset}
     */
    @Nullable
    public static TrackingToken createReplayToken(@Nullable TrackingToken tokenAtReset,
                                                  @Nullable TrackingToken startPosition) {
        return createReplayToken(tokenAtReset, startPosition, new byte[]{});
    }

    /**
     * Creates a new TrackingToken that represents the given {@code startPosition} of a stream. It will be in replay
     * state until the position of the provided {@code tokenAtReset}. After that, the {@code tokenAtReset} will become
     * the active token and the stream will no longer be considered as replaying.
     *
     * @param tokenAtReset  the token present when the reset was triggered
     * @param startPosition the position where the token should be reset to and start replaying from
     * @param resetContext  the context given to the reset, may be null
     * @return a token that represents a reset to the {@code startPosition} until the provided {@code tokenAtReset}
     */
    @Nullable
    public static TrackingToken createReplayToken(
            @Nullable TrackingToken tokenAtReset,
            @Nullable TrackingToken startPosition,
            byte[] resetContext
    ) {
        if (tokenAtReset == null) {
            return startPosition;
        }
        if (tokenAtReset instanceof ReplayToken) {
            return createReplayToken(((ReplayToken) tokenAtReset).tokenAtReset, startPosition, resetContext);
        }
        if (startPosition != null && isStrictlyAfter(startPosition, tokenAtReset)) {
            return startPosition;
        }

        boolean lastMessageWasReplay = WrappedToken.unwrapUpperBound(startPosition) == null || wasProcessedBeforeReset(
                tokenAtReset,
                startPosition);
        return new ReplayToken(
                tokenAtReset,
                startPosition,
                resetContext,
                lastMessageWasReplay
        );
    }

    /**
     * Indicates whether the given {@code trackingToken} represents a position that is part of a replay.
     *
     * @param trackingToken The token to verify
     * @return {@code true} if the token indicates a replay
     */
    public static boolean isReplay(TrackingToken trackingToken) {
        return WrappedToken.unwrap(trackingToken, ReplayToken.class)
                           .map(rt -> rt.isReplay())
                           .orElse(false);
    }

    /**
     * Indicates whether the given {@code trackingToken} is (1) a {@code ReplayToken} and (2) will conclude the replay.
     * <p>
     * A replay will be concluded when the {@link #getTokenAtReset()} {@link #covers(TrackingToken) covers} the
     * {@link #getCurrentToken()}.
     *
     * @param trackingToken the token to verify
     * @return {@code true} if the token is (1) a {@code ReplayToken} and (2) will conclude the replay.
     */
    public static boolean concludesReplay(TrackingToken trackingToken) {
        return WrappedToken.unwrap(trackingToken, ReplayToken.class)
                           .map(rt -> rt.getCurrentToken().covers(rt.getTokenAtReset()))
                           .orElse(false);
    }

    /**
     * Extracts the {@link ReplayToken#resetContext()} from the given {@code token}, converting it to the given
     * {@code type} with the given {@code converter}.
     * <p>
     * If the {@code token} is not a {@code ReplayToken}, or is not currently part of a replay, an empty
     * {@link Optional} will be returned.
     *
     * @param token     the token to extract and convert the {@link ReplayToken#resetContext()} from, if it is a
     *                  {@code ReplayToken}
     * @param type      the desired type to convert the {@link ReplayToken#resetContext()} with the given
     *                  {@code converter}
     * @param converter the {@code Converter} used to convert the {@link ReplayToken#resetContext()} with
     * @param <T>       the generic defining the desired type to convert the {@link ReplayToken#resetContext()} to
     * @return an {@code Optional} carrying the converted {@link ReplayToken#resetContext()}. Empty if the given
     * {@code token} was not of type {@code ReplayToken}, or is not currently part of a replay
     */
    public static <T> Optional<T> replayContext(
            TrackingToken token,
            Class<T> type,
            Converter converter
    ) {
        return WrappedToken.unwrap(token, ReplayToken.class)
                           .filter(rt -> rt.isReplay())
                           .map(ReplayToken::resetContext)
                           .map(rc -> converter.convert(rc, type));
    }

    /**
     * Return the relative position at which a reset was triggered for this Segment. In case a replay finished or no
     * replay is active, an {@code OptionalLong.empty()} will be returned.
     *
     * @param trackingToken the token to retrieve the token at reset from
     * @return the relative position at which a reset was triggered for this token
     */
    public static OptionalLong getTokenAtReset(TrackingToken trackingToken) {
        return WrappedToken.unwrap(trackingToken, ReplayToken.class)
                           .filter(rt -> rt.isReplay())
                           .map(rt -> rt.getTokenAtReset().position())
                           .orElse(OptionalLong.empty());
    }

    /**
     * Gets the token representing the position at which the reset was triggered.
     *
     * @return the token representing the position at which the reset was triggered
     */
    public TrackingToken getTokenAtReset() {
        return tokenAtReset;
    }

    /**
     * Gets the current token.
     *
     * @return the current token
     */
    public TrackingToken getCurrentToken() {
        return currentToken;
    }

    @Override
    public TrackingToken advancedTo(TrackingToken newToken) {
        if (tokenAtReset == null || isStrictlyAfter(newToken, tokenAtReset)) {
            // we're done replaying
            // if the token at reset was a wrapped token itself, we'll need to use that one to maintain progress.
            if (tokenAtReset instanceof WrappedToken) {
                return ((WrappedToken) tokenAtReset).advancedTo(newToken);
            }
            return newToken;
        }
        if (wasProcessedBeforeReset(tokenAtReset, newToken)) {
            // we're still well behind
            return new ReplayToken(
                    tokenAtReset,  // we don't do upperBound here because it influences what have been seen
                    newToken,
                    resetContext,
                    true
            );
        }
        // we're getting an event that we didn't have before, but we haven't finished replaying either
        if (tokenAtReset instanceof WrappedToken) {
            return new ReplayToken(tokenAtReset.upperBound(newToken),
                                   ((WrappedToken) tokenAtReset).advancedTo(newToken),
                                   resetContext,
                                   false);
        }
        return new ReplayToken(tokenAtReset.upperBound(newToken), newToken, resetContext, false);
    }

    private static boolean isStrictlyAfter(TrackingToken newToken, TrackingToken tokenAtReset) {
        return !newToken.samePositionAs(WrappedToken.unwrapUpperBound(tokenAtReset))
                && newToken.covers(WrappedToken.unwrapUpperBound(tokenAtReset))
                && !tokenAtReset.covers(WrappedToken.unwrapLowerBound(newToken));
    }

    /**
     * Determines if the event represented by newToken was delivered before the reset occurred.
     * <p>
     * This method leverages the {@link TrackingToken#lowerBound(TrackingToken)} behavior to detect whether an event was
     * already processed or was skipped (e.g., not yet committed) at reset time.
     * <p>
     * <b>How it works:</b>
     * <p>
     * The {@code lowerBound()} method computes the "earliest common position" of two tokens. When the minimum position
     * falls on a position that was not yet seen by one of the tokens, the algorithm walks backwards to find the first
     * position that both tokens have seen.
     * <p>
     * This "walk back" behavior naturally distinguishes:
     * <ul>
     *   <li><b>Events that were delivered:</b> {@code lowerBound} stays at the same position as newToken
     *       → {@code combinedLowerBound.samePositionAs(newToken)} returns {@code true}</li>
     *   <li><b>Events that were skipped:</b> {@code lowerBound} walks back past the skipped position
     *       → {@code combinedLowerBound.samePositionAs(newToken)} returns {@code false}</li>
     * </ul>
     *
     * @param tokenAtReset the token representing the position at reset
     * @param newToken     the token representing the current event position
     * @return {@code true} if the event was delivered before reset, {@code false} if it's a new event
     * @see TrackingToken#lowerBound(TrackingToken)
     */
    private static boolean wasProcessedBeforeReset(TrackingToken tokenAtReset, TrackingToken newToken) {
        TrackingToken resetLowerBound = WrappedToken.unwrapLowerBound(tokenAtReset);
        TrackingToken newTokenLowerBound = WrappedToken.unwrapLowerBound(newToken);
        TrackingToken resetTokenLowerNewToken = resetLowerBound.lowerBound(newTokenLowerBound);
        return resetTokenLowerNewToken.samePositionAs(newTokenLowerBound);
    }

    @Override
    public TrackingToken lowerBound(TrackingToken other) {
        if (other instanceof ReplayToken) {
            return new ReplayToken(this, ((ReplayToken) other).currentToken, resetContext);
        }
        return new ReplayToken(this, other, resetContext);
    }

    @Override
    public TrackingToken upperBound(TrackingToken other) {
        return advancedTo(other);
    }

    @Override
    public boolean covers(TrackingToken other) {
        if (other instanceof ReplayToken) {
            return currentToken != null && currentToken.covers(((ReplayToken) other).currentToken);
        }
        return currentToken != null && currentToken.covers(other);
    }

    @Override
    public boolean samePositionAs(TrackingToken other) {
        if (other instanceof ReplayToken) {
            return currentToken != null && currentToken.samePositionAs(((ReplayToken) other).currentToken);
        }
        return currentToken != null && currentToken.samePositionAs(other);
    }

    private boolean isReplay() {
        return lastMessageWasReplay;
    }

    @Override
    public TrackingToken lowerBound() {
        return WrappedToken.unwrapLowerBound(currentToken);
    }

    @Override
    public TrackingToken upperBound() {
        return WrappedToken.unwrapUpperBound(currentToken);
    }

    @Override
    public <R extends TrackingToken> Optional<R> unwrap(Class<R> tokenType) {
        if (tokenType.isInstance(this)) {
            return Optional.of(tokenType.cast(this));
        } else {
            return WrappedToken.unwrap(currentToken, tokenType);
        }
    }

    /**
     * Returns the context that was provided when the token was reset.
     *
     * @return the context, null if none was provided during reset
     */
    @JsonGetter
    public byte[] resetContext() {
        return resetContext;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ReplayToken that = (ReplayToken) o;
        return Objects.equals(tokenAtReset, that.tokenAtReset) &&
                Objects.equals(currentToken, that.currentToken) &&
                Arrays.equals(resetContext, that.resetContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tokenAtReset, currentToken, Arrays.hashCode(resetContext));
    }

    @Override
    public String toString() {
        return "ReplayToken{" +
                "currentToken=" + currentToken +
                ", tokenAtReset=" + tokenAtReset +
                ", context=" + Arrays.toString(resetContext) +
                '}';
    }

    @Override
    public OptionalLong position() {
        if (currentToken != null) {
            return currentToken.position();
        }
        // if we don't have a currentToken, we assume we're at the start
        return OptionalLong.of(0);
    }
}
