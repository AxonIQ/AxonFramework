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

package org.axonframework.messaging.eventhandling.tracing;

import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.common.Registration;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Type-preserving tracing decorator for {@link EventBus}.
 * <p>
 * Composes a {@link TracingEventSink} for the publish path (so a {@code TracingEventBus} produces the exact same
 * publish + commit span shape as a plain {@code TracingEventSink}) and passes
 * {@link #subscribe(BiFunction) subscribe} straight through to the wrapped {@link EventBus}.
 * <p>
 * Type preservation matters because AF5's component registry decorates by the slot's declared type
 * ({@link EventBus#getClass()}). Returning a plain {@code TracingEventSink} for an {@code EventBus.class} slot would
 * fail {@code DecoratedComponent}'s assignment check and abort configuration. Implementing {@code EventBus} directly
 * mirrors {@code InterceptingEventBus} -- the in-tree precedent for type-preserving event-bus decoration.
 *
 * @author Mateusz Nowak
 * @since 5.2.0
 */
@Internal
public final class TracingEventBus implements EventBus {

    private final EventBus delegate;
    private final TracingEventSink delegateSink;

    /**
     * Initializes a tracing {@link EventBus} wrapping the given {@code delegate}, obtaining publish spans from the
     * given {@code spanFactory}.
     *
     * @param delegate    the event bus to delegate to
     * @param spanFactory the factory producing the tracing spans
     */
    public TracingEventBus(EventBus delegate, SpanFactory spanFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.delegateSink = new TracingEventSink(delegate, spanFactory);
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
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("delegateSink", delegateSink);
    }
}
