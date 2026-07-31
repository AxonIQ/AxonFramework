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

import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Delegating {@link EventSink} decorator that opens tracing spans around event publication.
 * <p>
 * Publication is traced with a short-lived producer span per event, during which the span's tracing context is
 * injected into the event's metadata so a downstream handler can continue the trace.
 * <p>
 * This decorator is registered by {@code MessagingTracingConfigurationEnhancer}; it is never instantiated directly by
 * applications.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class TracingEventSink implements EventSink {

    /** Prefix for the per-event publish span ({@code "EventSink.publish <name>"}). */
    private static final String PUBLISH_SPAN = "EventSink.publish";

    private final EventSink delegate;
    private final SpanFactory spanFactory;

    /**
     * Initializes a tracing {@link EventSink} wrapping the given {@code delegate}, obtaining spans from the given
     * {@code spanFactory}.
     *
     * @param delegate    the event sink to delegate to
     * @param spanFactory the factory producing the tracing spans
     */
    public TracingEventSink(EventSink delegate, SpanFactory spanFactory) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.spanFactory = Objects.requireNonNull(spanFactory, "spanFactory may not be null");
    }

    @Override
    public CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                           List<? extends EventMessage> events) {
        List<EventMessage> propagated = new ArrayList<>(events.size());
        for (EventMessage event : events) {
            Span publishSpan = spanFactory.createDispatchSpan(
                    PUBLISH_SPAN + " " + event.type().qualifiedName().name(), event, context
            );
            // Duration is deliberately just the metadata-injection window, not the shared batch publish future below:
            // this span's job is to exist as this event's own dispatch node for downstream parent/link resolution,
            // not to time I/O that one publish() call performs for every event in the batch at once.
            propagated.add(publishSpan.branch(context, ignored -> publishSpan.propagateContext(event)));
        }
        return delegate.publish(context, propagated);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("spanFactory", spanFactory);
    }
}
