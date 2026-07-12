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

package org.axonframework.eventsourcing.eventstore.tracing;

import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.eventhandling.tracing.TracingEventSink;
import org.axonframework.common.Registration;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStoreTransaction;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Type-preserving tracing decorator for {@link EventStore}.
 * <p>
 * Composes a {@link TracingEventSink} for the publish path and passes every {@code EventStore}-specific operation
 * ({@link #transaction(ProcessingContext) transaction}, {@link #open(StreamingCondition, ProcessingContext) open}, the
 * {@code firstToken}/{@code latestToken}/{@code tokenAt}/{@code tokenSince} family, and
 * {@link #subscribe(BiFunction) subscribe}) straight through to the wrapped {@link EventStore}.
 * <p>
 * Implementing {@code EventStore} directly is required by AF5's component registry: a decorator registered against the
 * {@code EventStore.class} slot must return an instance assignable to that type (see
 * {@code DecoratedComponent#resolve}). Wrapping an {@code EventStore} in a plain {@code TracingEventSink} or
 * {@code TracingEventBus} would fail that assignment check and abort configuration.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class TracingEventStore implements EventStore {

    private final EventStore delegate;
    private final TracingEventSink delegateSink;

    /**
     * Initializes a tracing {@link EventStore} wrapping the given {@code delegate}, obtaining publish spans from the
     * given {@code spanFactory}.
     *
     * @param delegate    the event store to delegate to
     * @param spanFactory the factory producing the tracing spans
     */
    public TracingEventStore(EventStore delegate, SpanFactory spanFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.delegateSink = new TracingEventSink(delegate, spanFactory);
    }

    @Override
    public EventStoreTransaction transaction(ProcessingContext processingContext) {
        return delegate.transaction(processingContext);
    }

    @Override
    public CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                           List<? extends EventMessage> events) {
        return delegateSink.publish(context, events);
    }

    @Override
    public Registration subscribe(
            BiFunction<List<? extends EventMessage>, ProcessingContext, CompletableFuture<?>> eventsBatchConsumer
    ) {
        return delegate.subscribe(eventsBatchConsumer);
    }

    @Override
    public MessageStream<EventMessage> open(StreamingCondition condition, @Nullable ProcessingContext context) {
        return delegate.open(condition, context);
    }

    @Override
    public CompletableFuture<TrackingToken> firstToken(@Nullable ProcessingContext context) {
        return delegate.firstToken(context);
    }

    @Override
    public CompletableFuture<TrackingToken> latestToken(@Nullable ProcessingContext context) {
        return delegate.latestToken(context);
    }

    @Override
    public CompletableFuture<TrackingToken> tokenAt(Instant at, @Nullable ProcessingContext context) {
        return delegate.tokenAt(at, context);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("delegateSink", delegateSink);
    }
}
