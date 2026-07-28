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

package org.axonframework.hunt.harness;

import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecorder;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Writes every claim, extension and release one node makes into the run's history, and changes nothing else.
 * <p>
 * Ownership is not observable from the outside of a token store: the owner and the timestamp live in a row nobody
 * queries, and the only externally visible fact is that one node's call succeeded and another's did not. Recording
 * the calls at the seam is therefore the only way an ownership oracle gets anything to judge, and doing it in a
 * decorator is the only way to get it without touching the framework.
 * <p>
 * Each record carries the node's identity in {@code node} and the segment under {@code key}, so the checker can
 * group by segment and separate the nodes. An operation's invocation and completion are separate records, which
 * matters here more than anywhere else: a claim is granted somewhere between the two, so an interval derived from
 * them can be made deliberately conservative -- starting at the completion and expiring from the invocation -- and a
 * conservative interval never invents an overlap.
 * <p>
 * Storing a token is recorded too, and for a different reason. A claim says who may work on a segment; a stored token
 * says how far that work has got <em>durably</em>. Those are the two halves of the guarantee that a batch's handler
 * effects and its progress are persisted together, and without the second half a suite cannot see a batch whose effects
 * landed while its progress did not: the work package keeps its position in memory and carries on regardless, so the
 * omission is invisible until somebody re-reads the stored token. That is exactly the defect the mutation campaign
 * planted and the suite failed to catch before this record existed.
 * <p>
 * Deleting a token and listing segments carry neither decision, so recording them would bulk out every history in the
 * suite for nothing.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class RecordingTokenStore implements TokenStore {

    private final TokenStore delegate;
    private final HistoryRecorder.ProcessRecorder recorder;

    /**
     * Wraps one node's view of the run's token store.
     *
     * @param delegate the store the node really claims through
     * @param recorder the recorder stamped with that node's identity
     */
    public RecordingTokenStore(TokenStore delegate, HistoryRecorder.ProcessRecorder recorder) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate cannot be null.");
        this.recorder = Objects.requireNonNull(recorder, "The recorder cannot be null.");
    }

    @Override
    public CompletableFuture<List<Segment>> initializeTokenSegments(String processorName,
                                                                    int segmentCount,
                                                                    @Nullable TrackingToken initialToken,
                                                                    @Nullable ProcessingContext context) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(HistoryOps.PROCESSOR, processorName);
        arguments.put(HistoryOps.SEGMENT_COUNT, segmentCount);
        HistoryRecorder.Invocation invocation =
                recorder.invoke(HistoryOps.INIT_SEGMENTS, processorName, Map.copyOf(arguments));
        return record(delegate.initializeTokenSegments(processorName, segmentCount, initialToken, context),
                      invocation,
                      segments -> Map.of(HistoryOps.SEGMENT_COUNT, segments == null ? 0 : segments.size()));
    }

    @Override
    public CompletableFuture<TrackingToken> fetchToken(String processorName,
                                                       int segmentId,
                                                       @Nullable ProcessingContext context) {
        HistoryRecorder.Invocation invocation = claimInvocation(processorName, segmentId);
        return record(delegate.fetchToken(processorName, segmentId, context), invocation,
                      RecordingTokenStore::resumedAt);
    }

    @Override
    public CompletableFuture<TrackingToken> fetchToken(String processorName,
                                                       Segment segment,
                                                       @Nullable ProcessingContext context) {
        HistoryRecorder.Invocation invocation = claimInvocation(processorName, segment.getSegmentId());
        return record(delegate.fetchToken(processorName, segment, context), invocation,
                      RecordingTokenStore::resumedAt);
    }

    /**
     * Returns the position a granted claim tells the node to resume from, as the store's own answer rather than the
     * harness's opinion.
     * <p>
     * This is the number a redelivery licence is derived from, and it is the reason the completion of a claim carries
     * anything at all. A node taking a segment over resumes from exactly the token the store hands it, so every event
     * that segment had already delivered above that position may legitimately arrive again -- after a claim was
     * stolen, after a crashed node came back, after a merge gave the surviving segment the lower of two tokens, and
     * after a reset rewound every segment to the beginning. All four are the same fact, and all four are visible here.
     * A licence derived from elapsed time instead would forgive any repeat at all during a window; one derived from
     * this position forgives exactly the repeats the rewind explains.
     *
     * @param token the token the store granted, or {@code null} when the segment has no progress yet
     * @return the resume position, whether the framework calls the token a replay, and the position a replay rewound
     *     from
     */
    private static Map<String, Object> resumedAt(@Nullable TrackingToken token) {
        Map<String, Object> granted = new LinkedHashMap<>();
        granted.put(HistoryOps.POSITION, positionOf(token));
        boolean replay = token != null && ReplayToken.isReplay(token);
        granted.put(HistoryOps.REPLAY, replay);
        if (replay) {
            granted.put(HistoryOps.TOKEN_AT_RESET, ReplayToken.getTokenAtReset(token).orElse(-1L));
        }
        return Map.copyOf(granted);
    }

    @Override
    public CompletableFuture<Void> extendClaim(String processorName,
                                               int segmentId,
                                               @Nullable ProcessingContext context) {
        HistoryRecorder.Invocation invocation =
                recorder.invoke(HistoryOps.EXTEND, key(processorName, segmentId), segmentValue(processorName,
                                                                                              segmentId));
        return record(delegate.extendClaim(processorName, segmentId, context), invocation, ignored -> Map.of());
    }

    @Override
    public CompletableFuture<Void> releaseClaim(String processorName,
                                                int segmentId,
                                                @Nullable ProcessingContext context) {
        HistoryRecorder.Invocation invocation =
                recorder.invoke(HistoryOps.RELEASE, key(processorName, segmentId), segmentValue(processorName,
                                                                                                segmentId));
        return record(delegate.releaseClaim(processorName, segmentId, context), invocation, ignored -> Map.of());
    }

    @Override
    public CompletableFuture<Void> initializeSegment(@Nullable TrackingToken token,
                                                     String processorName,
                                                     Segment segment,
                                                     @Nullable ProcessingContext context) {
        HistoryRecorder.Invocation invocation =
                recorder.invoke(HistoryOps.INIT_SEGMENTS, key(processorName, segment.getSegmentId()),
                                segmentValue(processorName, segment.getSegmentId()));
        return record(delegate.initializeSegment(token, processorName, segment, context), invocation,
                      ignored -> Map.of());
    }

    @Override
    public CompletableFuture<Void> storeToken(@Nullable TrackingToken token,
                                              String processorName,
                                              int segmentId,
                                              @Nullable ProcessingContext context) {
        Map<String, Object> arguments = new LinkedHashMap<>(segmentValue(processorName, segmentId));
        arguments.put(HistoryOps.POSITION, positionOf(token));
        arguments.put(HistoryOps.REPLAY, token != null && ReplayToken.isReplay(token));
        HistoryRecorder.Invocation invocation =
                recorder.invoke(HistoryOps.STORE_TOKEN, key(processorName, segmentId), Map.copyOf(arguments));
        return record(delegate.storeToken(token, processorName, segmentId, context), invocation, ignored -> Map.of());
    }

    /**
     * Returns the position a token reports, or {@code -1} when it reports none.
     * <p>
     * A replay token is unwrapped first: what matters for durable progress is the position the segment has really
     * reached, and a replay token's own position is the rewound one.
     */
    static long positionOf(@Nullable TrackingToken token) {
        if (token == null) {
            return -1L;
        }
        return token.position().orElse(-1L);
    }

    @Override
    public CompletableFuture<Void> deleteToken(String processorName,
                                               int segmentId,
                                               @Nullable ProcessingContext context) {
        return delegate.deleteToken(processorName, segmentId, context);
    }

    @Override
    public CompletableFuture<Segment> fetchSegment(String processorName,
                                                   int segmentId,
                                                   @Nullable ProcessingContext context) {
        return delegate.fetchSegment(processorName, segmentId, context);
    }

    @Override
    public CompletableFuture<List<Segment>> fetchSegments(String processorName,
                                                          @Nullable ProcessingContext context) {
        return delegate.fetchSegments(processorName, context);
    }

    @Override
    public CompletableFuture<List<Segment>> fetchAvailableSegments(String processorName,
                                                                   @Nullable ProcessingContext context) {
        return delegate.fetchAvailableSegments(processorName, context);
    }

    @Override
    public CompletableFuture<String> retrieveStorageIdentifier(@Nullable ProcessingContext context) {
        return delegate.retrieveStorageIdentifier(context);
    }

    private HistoryRecorder.Invocation claimInvocation(String processorName, int segmentId) {
        return recorder.invoke(HistoryOps.CLAIM, key(processorName, segmentId),
                               segmentValue(processorName, segmentId));
    }

    private static Map<String, Object> segmentValue(String processorName, int segmentId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(HistoryOps.PROCESSOR, processorName);
        value.put(HistoryOps.SEGMENT, segmentId);
        return Map.copyOf(value);
    }

    private static String key(String processorName, int segmentId) {
        return processorName + "/" + segmentId;
    }

    private static <R> CompletableFuture<R> record(CompletableFuture<R> future,
                                                   HistoryRecorder.Invocation invocation,
                                                   java.util.function.Function<R, Map<String, Object>> outcome) {
        return future.whenComplete((result, failure) -> {
            if (failure == null) {
                invocation.ok(outcome.apply(result));
            } else {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
                invocation.fail(cause.getClass().getSimpleName(), Map.of());
            }
        });
    }
}
