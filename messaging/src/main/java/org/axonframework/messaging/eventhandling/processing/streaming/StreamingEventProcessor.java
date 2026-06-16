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

package org.axonframework.messaging.eventhandling.processing.streaming;

import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.EventTrackerStatus;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToRetrieveIdentifierException;
import org.axonframework.messaging.eventstreaming.TrackingTokenSource;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


/**
 * An {@link EventProcessor} which processes an event stream in segments. Segmenting the stream of events allows for
 * parallelization of event processing, effectively enhancing the processing speed.
 * <p>
 * A {@link StreamingEventProcessor} uses a {@link TokenStore} to store the progress of each of the segments it is
 * processing. Furthermore, it allows for segment interactions like:
 * <ul>
 *     <li>{@link #releaseSegment(int)} - release a segment processed by this processor</li>
 *     <li>{@link #splitSegment(int)} - increase the number of segments by splitting one into two</li>
 *     <li>{@link #mergeSegment(int)} - decrease the number of segments by merging two segments into one</li>
 *     <li>{@link #resetTokens()} - adjust the positions of all segments for this processor to the beginning of the event stream</li>
 *     <li>{@link #processingStatus()} - return the {@link EventTrackerStatus} of every segment processed by this instance</li>
 * </ul>
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 4.5
 */
public interface StreamingEventProcessor extends EventProcessor {

    /**
     * Returns the unique identifier of the {@link TokenStore} used by this {@link StreamingEventProcessor}.
     * <p>
     * The identifier is resolved eagerly during {@link #start()} when the processor has been started. If called
     * before {@link #start()} has completed, implementations may resolve the identifier lazily with a blocking call
     * to the {@link TokenStore}.
     *
     * @return the unique identifier of the {@link TokenStore} used by this {@link StreamingEventProcessor}
     * @throws UnableToRetrieveIdentifierException if the {@link TokenStore} was unable to retrieve the identifier
     */
    String getTokenStoreIdentifier();

    /**
     * Instructs the processor to release the segment with given {@code segmentId}.
     *
     * @param segmentId the id of the segment to release
     * @return a {@link CompletableFuture} which completes when the operation completes
     */
    CompletableFuture<Void> releaseSegment(int segmentId);

    /**
     * Instructs the processor to release the segment with given {@code segmentId}. This processor will not try to claim
     * the given segment for the specified {@code releaseDuration} in the given {@code unit}, to ensure it is not
     * immediately reclaimed. Note that this will override any previous release duration that existed for this segment.
     * Providing a negative value will allow the segment to be immediately claimed.
     * <p>
     * If the processor is not actively processing the segment with given {@code segmentId}, claiming it will be ignored
     * for the given timeframe nonetheless.
     *
     * @param segmentId       the id of the segment to be released for the specified {@code releaseDuration}
     * @param releaseDuration the amount of time to disregard {@code segmentId} for processing
     * @param unit            the unit of time used to express the {@code releaseDuration}
     * @return a {@link CompletableFuture} which completes when the operation completes
     */
    CompletableFuture<Void> releaseSegment(int segmentId, long releaseDuration, TimeUnit unit);

    /**
     * Instructs the processor to claim the segment with given {@code segmentId} and start processing it as soon as
     * possible.
     * <p>
     * The given {@code segmentId} must not be currently processed by a different processor instance, as that will have
     * an active claim on the token. Claiming a segment that is already being processed will have no effect and return
     * {@code true}.
     * <p>
     * A {@code true} return value indicates that the segment has been claimed and will be processed by this processor.
     * The {@link StreamingEventProcessor} may postpone start of work until after completion of this task, as long as
     * the token has been claimed so work can be started. A return value of {@code false} indicates that the segment has
     * not been claimed due to the token for that segment not being available.
     *
     * @param segmentId the identifier of the segment to claim and start processing
     * @return a {@link CompletableFuture} providing the result of the claim operation
     */
    default CompletableFuture<Boolean> claimSegment(int segmentId) {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Instruct the processor to split the segment with given {@code segmentId} into two segments, allowing an
     * additional process to start processing events from it.
     * <p>
     * To be able to split segments, the {@link TokenStore} configured with this processor must use explicitly
     * initialized tokens. Also, the given {@code segmentId} must be currently processed by a process owned by this
     * processor instance.
     *
     * @param segmentId the identifier of the segment to split
     * @return a {@link CompletableFuture} providing the result of the split operation
     */
    CompletableFuture<Boolean> splitSegment(int segmentId);

    /**
     * Instruct the processor to merge the segment with given {@code segmentId} back with the segment that it was
     * originally split from. The processor must be able to claim the other segment, in order to merge it. Therefore,
     * this other segment must not have any active claims in the {@link TokenStore}.
     * <p>
     * The processor must currently be actively processing the segment with given {@code segmentId}.
     * <p>
     * Use {@link #releaseSegment(int)} to force this processor to release any claims with tokens required to merge the
     * segments.
     * <p>
     * To find out which segment a given {@code segmentId} should be merged with, use the following procedure:
     * <pre>
     *     EventTrackerStatus status = processor.processingStatus().get(segmentId);
     *     if (status == null) {
     *         // this processor is not processing segmentId, and will not be able to merge
     *     }
     *     return status.getSegment().mergeableSegmentId();
     * </pre>
     *
     * @param segmentId the identifier of the segment to merge
     * @return a {@link CompletableFuture} indicating whether the merge was executed successfully
     */
    CompletableFuture<Boolean> mergeSegment(int segmentId);

    /**
     * Indicates whether this {@link StreamingEventProcessor} supports a "reset". Generally, a reset is supported if at
     * least one of the Event Handling Components assigned to this processor supports it, and no handlers explicitly
     * prevent the resets.
     * <p>
     * This method should be invoked prior to invoking any of the {@link #resetTokens()} operations as an early
     * validation.
     *
     * @return {@code true} if resets are supported, {@code false} otherwise
     */
    boolean supportsReset();

    /**
     * Resets tokens to their initial state. This effectively causes a replay.
     * <p>
     * Before attempting to reset the tokens, the caller must stop this processor, as well as any instances of the same
     * logical processor that may be running in the cluster. Failure to do so will cause the reset to fail, as a
     * processor can only reset the tokens if it is able to claim them all.
     *
     * @return a {@link CompletableFuture} which completes when the operation completes
     */
    CompletableFuture<Void> resetTokens();

    /**
     * Resets tokens to their initial state. This effectively causes a replay. The given {@code resetContext} will be
     * used to support the (optional) reset operation in an Event Handling Component.
     * <p>
     * Before attempting to reset the tokens, the caller must stop this processor, as well as any instances of the same
     * logical processor that may be running in the cluster. Failure to do so will cause the reset to fail, as a
     * processor can only reset the tokens if it is able to claim them all.
     *
     * @param resetContext a {@code R} used to support the reset operation
     * @param <R>          the type of the provided {@code resetContext}
     * @return a {@link CompletableFuture} which completes when the operation completes
     */
    <R> CompletableFuture<Void> resetTokens(@Nullable R resetContext);

    /**
     * Reset tokens to the position as return by the given {@code initialTrackingTokenSupplier}. This effectively causes
     * a replay since that position.
     * <p>
     * Note that the new token must represent a position that is <em>before</em> the current position of the processor.
     * <p>
     * Before attempting to reset the tokens, the caller must stop this processor, as well as any instances of the same
     * logical processor that may be running in the cluster. Failure to do so will cause the reset to fail, as a
     * processor can only reset the tokens if it is able to claim them all.
     *
     * @param initialTrackingTokenSupplier a function returning the token representing the position to reset to
     * @return a {@link CompletableFuture} which completes when the operation completes
     */
    CompletableFuture<Void> resetTokens(
            Function<TrackingTokenSource, CompletableFuture<TrackingToken>> initialTrackingTokenSupplier
    );

    /**
     * Reset tokens to the position as return by the given {@code initialTrackingTokenSupplier}. This effectively causes
     * a replay since that position. The given {@code resetContext} will be used to support the (optional) reset
     * operation in an Event Handling Component.
     * <p>
     * Note that the new token must represent a position that is <em>before</em> the current position of the processor.
     * <p>
     * Before attempting to reset the tokens, the caller must stop this processor, as well as any instances of the same
     * logical processor that may be running in the cluster. Failure to do so will cause the reset to fail, as a
     * processor can only reset the tokens if it is able to claim them all.
     *
     * @param initialTrackingTokenSupplier a function returning the token representing the position to reset to
     * @param resetContext                 a {@code R} used to support the reset operation
     * @param <R>                          the type of the provided {@code resetContext}
     * @return a {@link CompletableFuture} which completes when the operation completes
     */
    <R> CompletableFuture<Void> resetTokens(
            Function<TrackingTokenSource, CompletableFuture<TrackingToken>> initialTrackingTokenSupplier,
            @Nullable R resetContext
    );

    /**
     * Resets tokens to the given {@code startPosition}. This effectively causes a replay of events since that
     * position.
     * <p>
     * Note that the new token must represent a position that is <em>before</em> the current position of the processor.
     * <p>
     * Before attempting to reset the tokens, the caller must stop this processor, as well as any instances of the same
     * logical processor that may be running in the cluster. Failure to do so will cause the reset to fail, as a
     * processor can only reset the tokens if it is able to claim them all.
     *
     * @param startPosition the token representing the position to reset the processor to
     * @return a {@link CompletableFuture} which completes when the operation completes
     */
    default CompletableFuture<Void> resetTokens(TrackingToken startPosition) {
        return resetTokens(startPosition, null);
    }

    /**
     * Resets tokens to the given {@code startPosition}. This effectively causes a replay of events since that position.
     * The given {@code resetContext} will be used to support the (optional) reset operation in an Event Handling
     * Component.
     * <p>
     * Note that the new token must represent a position that is <em>before</em> the current position of the processor.
     * <p>
     * Before attempting to reset the tokens, the caller must stop this processor, as well as any instances of the same
     * logical processor that may be running in the cluster. Failure to do so will cause the reset to fail, as a
     * processor can only reset the tokens if it is able to claim them all.
     *
     * @param startPosition the token representing the position to reset the processor to
     * @param resetContext  a {@code R} used to support the reset operation
     * @param <R>           the type of the provided {@code resetContext}
     * @return a {@link CompletableFuture} which completes when the operation completes
     */
    <R> CompletableFuture<Void> resetTokens(TrackingToken startPosition, @Nullable R resetContext);

    /**
     * Specifies the maximum amount of segments this {@link EventProcessor} can process at the same time.
     *
     * @return the maximum amount of segments this {@link EventProcessor} can process at the same time
     */
    int maxCapacity();

    /**
     * Returns the status for each of the segments processed by this processor as {@link EventTrackerStatus} instances.
     * The key of the {@link Map} represent the segment ids processed by this instance. The values of the returned
     * {@code Map} represent the last known status of that segment.
     * <p>
     * Note that the returned {@link Map} is unmodifiable, but does reflect any changes made to the status as the
     * processor is processing Events.
     *
     * @return the status for each of the segments processed by the current processor
     */
    Map<Integer, EventTrackerStatus> processingStatus();

    /**
     * Returns the overall replay status of <b>this</b> {@link StreamingEventProcessor}. Any other instances of this
     * streaming processor running on other applications are <b>not</b> not taken into account in this calculation.
     * <p>
     * Note that when an {@link EventTrackerStatus} returns {@code true} for both
     * {@link EventTrackerStatus#isReplaying()} and {@link EventTrackerStatus#isCaughtUp()}, that the replay is done but
     * the processor did not handle any new events yet.
     *
     * @return {@code true} if any of the segments is still replaying and not caught up yet, {@code false} otherwise
     */
    default boolean isReplaying() {
        return processingStatus().values().stream()
                                 .anyMatch(trackerStatus -> !trackerStatus.isCaughtUp() && trackerStatus.isReplaying());
    }
}