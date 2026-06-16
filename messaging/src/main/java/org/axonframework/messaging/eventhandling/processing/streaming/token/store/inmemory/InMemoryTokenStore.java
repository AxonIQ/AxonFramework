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

package org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToClaimTokenException;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToInitializeTokenException;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.axonframework.common.ObjectUtils.getOrDefault;

/**
 * Implementation of a {@link TokenStore} that stores tracking tokens in memory. This implementation is thread-safe.
 *
 * @author Rene de Waele
 * @author Christophe Bouhier
 * @since 3.0.0
 */
public class InMemoryTokenStore implements TokenStore {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final GlobalSequenceTrackingToken NULL_TOKEN = new GlobalSequenceTrackingToken(-1);

    private final Map<ProcessAndSegmentId, SegmentAndToken> tokens = new ConcurrentHashMap<>();
    private final String identifier = UUID.randomUUID().toString();

    /**
     * No-arg constructor which will log a warning on initialization.
     */
    public InMemoryTokenStore() {
        logger.warn(
                """
                        An in memory token store is being created.
                        This means the event processor using this token store might process the same events again when the application is restarted.
                        If the use of an in memory token store is intentional, this warning can be ignored.
                        If the tokens should be persisted, use the JPA, JDBC or MongoDB token store instead.
                        """
        );
    }

    @Override
    public CompletableFuture<List<Segment>> initializeTokenSegments(
            String processorName,
            int segmentCount,
            @Nullable TrackingToken initialToken,
            @Nullable ProcessingContext context
    ) throws UnableToClaimTokenException {
        return fetchSegments(processorName, context)
                .thenApply(segments -> {
                    if (segments.size() > 0) {
                        throw new UnableToClaimTokenException(
                                "Could not initialize segments. Some segments were already present.");
                    }

                    List<Segment> newSegments = Segment.splitBalanced(Segment.ROOT_SEGMENT, segmentCount - 1);

                    for (Segment segment : newSegments) {
                        tokens.put(new ProcessAndSegmentId(processorName, segment.getSegmentId()),
                                   new SegmentAndToken(segment, getOrDefault(initialToken, NULL_TOKEN)));
                    }

                    return newSegments;
                });
    }

    @Override
    public CompletableFuture<Void> storeToken(@Nullable TrackingToken token,
                                              String processorName,
                                              int segmentId,
                                              @Nullable ProcessingContext context) {
        Objects.requireNonNull(context, "processingContext may not be null for an InMemoryTokenStore");
        ProcessAndSegmentId key = new ProcessAndSegmentId(processorName, segmentId);
        if (!tokens.containsKey(key)) {
            return noSuchToken(processorName, segmentId);
        }
        if (context.isStarted()) {
            context.runOnAfterCommit(c -> {
                if (!updateToken(token, key)) {
                    throw noSuchTokenException(processorName, segmentId);
                }
            });
        } else if (!updateToken(token, key)) {
            return noSuchToken(processorName, segmentId);
        }
        return FutureUtils.emptyCompletedFuture();
    }

    private boolean updateToken(@Nullable TrackingToken token, ProcessAndSegmentId key) {
        SegmentAndToken old = tokens.computeIfPresent(key, (ps, st) -> new SegmentAndToken(st.segment, token));

        return old != null;
    }

    @Override
    public CompletableFuture<TrackingToken> fetchToken(String processorName,
                                                       int segmentId,
                                                       @Nullable ProcessingContext context) {
        SegmentAndToken st = tokens.get(new ProcessAndSegmentId(processorName, segmentId));
        if (st == null) {
            return failedFuture(new UnableToClaimTokenException(
                    "No token was initialized for segment " + segmentId + " for processor " + processorName));
        } else if (NULL_TOKEN == st.trackingToken) {
            return FutureUtils.emptyCompletedFuture();
        }
        return completedFuture(st.trackingToken);
    }

    @Override
    public CompletableFuture<Void> releaseClaim(String processorName,
                                                int segment,
                                                @Nullable ProcessingContext context) {
        // no-op, the in-memory implementation isn't accessible by multiple processes
        return FutureUtils.emptyCompletedFuture();
    }

    @Override
    public CompletableFuture<Void> deleteToken(
            String processorName,
            int segment,
            @Nullable ProcessingContext context
    ) throws UnableToClaimTokenException {
        tokens.remove(new ProcessAndSegmentId(processorName, segment));
        return FutureUtils.emptyCompletedFuture();
    }

    @Override
    public CompletableFuture<Void> initializeSegment(
            @Nullable TrackingToken token,
            String processorName,
            Segment segment,
            @Nullable ProcessingContext context
    ) throws UnableToInitializeTokenException {
        SegmentAndToken previous = tokens.putIfAbsent(new ProcessAndSegmentId(processorName, segment.getSegmentId()),
                                                      new SegmentAndToken(segment, token == null ? NULL_TOKEN : token));
        if (previous != null) {
            return failedFuture(new UnableToInitializeTokenException("Token was already present"));
        }
        return completedFuture(null);
    }

    @Override
    public CompletableFuture<Segment> fetchSegment(String processorName, int segmentId, @Nullable ProcessingContext context) {
        SegmentAndToken st = tokens.get(new ProcessAndSegmentId(processorName, segmentId));

        return completedFuture(st == null ? null : st.segment);
    }

    @Override
    public CompletableFuture<List<Segment>> fetchSegments(String processorName,
                                                          @Nullable ProcessingContext context) {
        return completedFuture(tokens.entrySet().stream()
                                     .filter(e -> e.getKey().processorName.equals(processorName))
                                     .map(e -> e.getValue().segment)
                                     .distinct()
                                     .toList()
        );
    }

    @Override
    public CompletableFuture<List<Segment>> fetchAvailableSegments(String processorName, ProcessingContext context) {
        return fetchSegments(processorName, context);
    }

    @Override
    public CompletableFuture<String> retrieveStorageIdentifier(@Nullable ProcessingContext context) {
        return completedFuture(identifier);
    }

    private CompletableFuture<Void> noSuchToken(String processorName, int segmentId) {
        return failedFuture(noSuchTokenException(processorName, segmentId));
    }

    private UnableToClaimTokenException noSuchTokenException(String processorName, int segmentId) {
        return new UnableToClaimTokenException(
                "No such token for processor '%s' and segment %d".formatted(processorName, segmentId));
    }

    private record SegmentAndToken(Segment segment, TrackingToken trackingToken) {}
    private record ProcessAndSegmentId(String processorName, int segmentId) {}
}
