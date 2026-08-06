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

package org.axonframework.hunt.scenario;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Forwards every {@link TokenStore} call to a delegate, so a probe can override the one call it perturbs or records
 * and leave the rest of the store's behaviour untouched.
 */
abstract class ForwardingTokenStore implements TokenStore {

    private final TokenStore delegate;

    ForwardingTokenStore(TokenStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "The delegate cannot be null.");
    }

    @Override
    public CompletableFuture<List<Segment>> initializeTokenSegments(String processorName,
                                                                    int segmentCount,
                                                                    @Nullable TrackingToken initialToken,
                                                                    @Nullable ProcessingContext context) {
        return delegate.initializeTokenSegments(processorName, segmentCount, initialToken, context);
    }

    @Override
    public CompletableFuture<Void> storeToken(@Nullable TrackingToken token,
                                              String processorName,
                                              int segmentId,
                                              @Nullable ProcessingContext context) {
        return delegate.storeToken(token, processorName, segmentId, context);
    }

    @Override
    public CompletableFuture<TrackingToken> fetchToken(String processorName,
                                                       int segmentId,
                                                       @Nullable ProcessingContext context) {
        return delegate.fetchToken(processorName, segmentId, context);
    }

    @Override
    public CompletableFuture<Void> releaseClaim(String processorName,
                                                int segmentId,
                                                @Nullable ProcessingContext context) {
        return delegate.releaseClaim(processorName, segmentId, context);
    }

    @Override
    public CompletableFuture<Void> initializeSegment(@Nullable TrackingToken token,
                                                     String processorName,
                                                     Segment segment,
                                                     @Nullable ProcessingContext context) {
        return delegate.initializeSegment(token, processorName, segment, context);
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
    public CompletableFuture<List<Segment>> fetchSegments(String processorName, @Nullable ProcessingContext context) {
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
}
