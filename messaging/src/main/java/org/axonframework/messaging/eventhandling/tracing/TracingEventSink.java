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
 * Publication is traced with two span shapes, mirroring the established publish/commit pattern:
 * <ul>
 *     <li>a short-lived <em>publish</em> span (kind producer) per event, during which this span's tracing context is
 *     injected into the event's metadata so a downstream handler can continue the trace; and</li>
 *     <li>a single <em>commit</em> span (kind internal) around the actual publication. When a
 *     {@link ProcessingContext} is present the commit span is bound to that context's lifecycle (it ends when the
 *     unit of work completes); otherwise it is opened around the synchronous publication and ended when the resulting
 *     future completes.</li>
 * </ul>
 * <p>
 * This decorator is registered by {@code MessagingTracingConfigurationEnhancer}; it is never instantiated directly by
 * applications.
 *
 * @author Mateusz Nowak
 * @since 5.2.0
 */
@Internal
public final class TracingEventSink implements EventSink {

    /** Prefix for the per-event publish span ({@code "EventBus.publishEvent <name>"}). */
    public static final String PUBLISH_SPAN = "EventBus.publishEvent";

    /** Name of the unit-of-work-scoped event-commit span. */
    public static final String COMMIT_SPAN = "EventBus.commitEvents";

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
            propagated.add(publishSpan.runSupplier(() -> publishSpan.propagateContext(event)));
        }
        Span commitSpan = spanFactory.createInternalSpan(COMMIT_SPAN, context);
        if (context != null) {
            commitSpan.start(context);
            return delegate.publish(context, propagated);
        }
        return commitSpan.runSupplierAsync(() -> delegate.publish(null, propagated));
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("spanFactory", spanFactory);
    }
}
