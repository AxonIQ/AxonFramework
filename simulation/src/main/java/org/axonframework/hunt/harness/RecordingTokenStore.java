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
 * Only the ownership-bearing calls are recorded. Storing a token, deleting one and listing segments carry no claim
 * decision, so recording them would bulk out every history in the suite for nothing.
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
        return record(delegate.fetchToken(processorName, segmentId, context), invocation, token -> Map.of());
    }

    @Override
    public CompletableFuture<TrackingToken> fetchToken(String processorName,
                                                       Segment segment,
                                                       @Nullable ProcessingContext context) {
        HistoryRecorder.Invocation invocation = claimInvocation(processorName, segment.getSegmentId());
        return record(delegate.fetchToken(processorName, segment, context), invocation, token -> Map.of());
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
        return delegate.storeToken(token, processorName, segmentId, context);
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
