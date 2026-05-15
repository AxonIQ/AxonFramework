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

package org.axonframework.messaging.eventstreaming;

import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Provides functionality to retrieve {@link TrackingToken TrackingTokens} for the head and tail of the stream, and at a
 * given point in time in the stream.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Rene de Waele
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface TrackingTokenSource {

    /**
     * Creates a {@link TrackingToken} representing the <b>first</b> position of the
     * {@link MessageStream event stream}.
     * <p>
     * As the retrieved token represents the point from which to {@link StreamableEventSource#open(StreamingCondition,
     * ProcessingContext) open} the event stream, the first event to be streamed when opening is the one
     * right after the returned token.
     * <p>
     * Subsequent invocation of this method will yield the same result, <em>unless</em> the stream's initial values are
     * deleted.
     *
     * @param context The current {@link ProcessingContext}, if any.
     * @return A {@link CompletableFuture} of a {@link TrackingToken} representing the <b>first</b> event of the
     * {@link MessageStream event stream}.
     */
    CompletableFuture<TrackingToken> firstToken(@Nullable ProcessingContext context);

    /**
     * Creates a {@link TrackingToken} representing the <b>latest</b> position, thus pointing at the next event of the
     * {@link MessageStream event stream}.
     * <p>
     * As the retrieved token represents the point from which to {@link StreamableEventSource#open(StreamingCondition,
     * ProcessingContext) open} the event stream, the first event to be streamed when opening is the one right after the
     * returned token.
     * <p>
     * Since the {@link MessageStream event stream} of this source is theoretically <em>infinite</em>, subsequent
     * invocation of this operation typically return a different token. Only if this {@code StreamableEventSource} is
     * idle, will several {@code latestToken()} invocations result in the same {@code TrackingToken}.
     *
     * @param context The current {@link ProcessingContext}, if any.
     * @return A {@link CompletableFuture} of a {@link TrackingToken} representing the <b>latest</b> event, thus
     * pointing at the next event of the {@link MessageStream event stream}.
     */
    CompletableFuture<TrackingToken> latestToken(@Nullable ProcessingContext context);

    /**
     * Creates a {@link TrackingToken} tracking all {@link EventMessage events} after the given {@code at} from an
     * {@link MessageStream event stream}.
     * <p>
     * When there is an {@link EventMessage} exactly at the given {@code dateTime}, it will be tracked too.
     *
     * @param at      The {@link Instant} determining how the {@link TrackingToken} should be created. The returned token
     *                points at very first event before this {@code Instant}.
     * @param context The current {@link ProcessingContext}, if any.
     * @return A {@link CompletableFuture} of {@link TrackingToken} pointing at the very first event before the given
     * {@code at} of the {@link MessageStream event stream}.
     */
    CompletableFuture<TrackingToken> tokenAt(Instant at, @Nullable ProcessingContext context);
}
