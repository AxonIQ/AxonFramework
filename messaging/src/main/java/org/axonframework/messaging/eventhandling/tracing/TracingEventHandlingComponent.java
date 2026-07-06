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

import org.axonframework.messaging.tracing.NoOpSpanFactory;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChanged;
import org.axonframework.messaging.eventhandling.replay.ResetContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Delegating {@link EventHandlingComponent} decorator that opens a per-event handler span around each event handled,
 * and -- for streaming-processor batches -- a single enclosing batch span.
 * <p>
 * On each {@link #handle(EventMessage, ProcessingContext)} a handler span (kind consumer) is opened and bound to the
 * processing context's lifecycle; its parent is resolved from the event's propagated metadata (continuing the
 * publishing trace) or, absent that, the active span on the context. The batch span is opened lazily on the first
 * event of a batch (via {@link ProcessingContext#computeResourceIfAbsent}) and only for streaming processors --
 * detected by the presence of a {@link Segment} on the context, written exclusively by the pooled-streaming work
 * package. Subscribing processors run inside the publisher's unit of work and therefore get no separate batch span,
 * matching the established trace shape.
 * <p>
 * This decorator is registered by {@code MessagingTracingConfigurationEnhancer}; it is never instantiated directly by
 * applications.
 *
 * @author Mateusz Nowak
 * @since 5.2.0
 */
@Internal
public final class TracingEventHandlingComponent implements EventHandlingComponent {

    /** Prefix for the per-event handler span ({@code "EventProcessor.process <name>"}). */
    public static final String PROCESS_SPAN = "EventProcessor.process";

    /** Name of the streaming-event-processor batch root span. */
    public static final String BATCH_SPAN = "StreamingEventProcessor.batch";

    private static final Context.ResourceKey<Span> BATCH_SPAN_KEY =
            Context.ResourceKey.withLabel("org.axonframework.messaging.tracing.batchSpan");

    private final EventHandlingComponent delegate;
    private final SpanFactory spanFactory;
    private final boolean disableBatchTrace;
    private final boolean distributedInSameTrace;
    private final Duration distributedInSameTraceTimeLimit;

    /**
     * Initializes a tracing {@link EventHandlingComponent} with the default sub-toggles
     * ({@code disableBatchTrace=false}, {@code distributedInSameTrace=false},
     * {@code distributedInSameTraceTimeLimit=PT2M}).
     *
     * @param delegate    the event-handling component to delegate to
     * @param spanFactory the factory producing the tracing spans
     */
    public TracingEventHandlingComponent(EventHandlingComponent delegate, SpanFactory spanFactory) {
        this(delegate, spanFactory, false, false, Duration.ofMinutes(2));
    }

    /**
     * Initializes a tracing {@link EventHandlingComponent} wrapping the given {@code delegate}, obtaining spans from
     * the given {@code spanFactory}.
     *
     * @param delegate                        the event-handling component to delegate to
     * @param spanFactory                     the factory producing the tracing spans
     * @param disableBatchTrace               when {@code true}, no enclosing batch span is opened for streaming-processor batches
     * @param distributedInSameTrace          when {@code true}, the handler span continues the publisher's trace via {@link SpanFactory#createHandlerSpan(String, Message, ProcessingContext)}; when {@code false} (default, AF4 parity), a new trace is started and linked back to the publisher via {@link SpanFactory#createDisconnectedHandlerSpan(String, Message, ProcessingContext)}
     * @param distributedInSameTraceTimeLimit how recent an event must be to continue the publisher's trace when {@code distributedInSameTrace} is {@code true}; older events (e.g. replays) start their own trace linked back to the publisher instead of stretching the publisher's long-finished trace (AF4 parity)
     */
    public TracingEventHandlingComponent(EventHandlingComponent delegate,
                                         SpanFactory spanFactory,
                                         boolean disableBatchTrace,
                                         boolean distributedInSameTrace,
                                         Duration distributedInSameTraceTimeLimit) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.spanFactory = Objects.requireNonNull(spanFactory, "spanFactory may not be null");
        this.disableBatchTrace = disableBatchTrace;
        this.distributedInSameTrace = distributedInSameTrace;
        this.distributedInSameTraceTimeLimit =
                Objects.requireNonNull(distributedInSameTraceTimeLimit,
                                       "distributedInSameTraceTimeLimit may not be null");
    }

    @Override
    public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
        // Open the batch span once per batch, on the first event -- unless disabled by configuration. Note: the span
        // is started (which mutates the context via putResource) OUTSIDE a computeResourceIfAbsent supplier --
        // mutating a ProcessingContext from within such a supplier is rejected as a re-entrant ("recursive") update.
        if (!disableBatchTrace && context.getResource(BATCH_SPAN_KEY) == null) {
            context.putResource(BATCH_SPAN_KEY, openBatchSpan(context));
        }
        String spanName = PROCESS_SPAN + " " + event.type().qualifiedName().name();
        Span handlerSpan = continuesPublisherTrace(event)
                ? spanFactory.createHandlerSpan(spanName, event, context)
                : spanFactory.createDisconnectedHandlerSpan(spanName, event, context);
        handlerSpan.start(context);
        return delegate.handle(event, context);
    }

    /**
     * Decides whether the per-event handler span continues the publisher's trace: only when
     * {@code distributedInSameTrace} is enabled AND the event is younger than
     * {@code distributedInSameTraceTimeLimit}. Stale events -- typically replays -- get a disconnected span (new trace
     * linked back to the publisher) so they do not stretch a long-finished trace (AF4 parity).
     */
    private boolean continuesPublisherTrace(EventMessage event) {
        return distributedInSameTrace
                && !event.timestamp().isBefore(Instant.now().minus(distributedInSameTraceTimeLimit));
    }

    /**
     * Opens the batch span for a streaming-processor batch, bound to the batch unit of work's lifecycle. For a
     * subscribing processor (no {@link Segment} on the context) no batch span is opened; a no-op span is returned as
     * the once-per-batch sentinel so the lazy-open is not retried for every event.
     */
    private Span openBatchSpan(ProcessingContext context) {
        if (Segment.fromContext(context).isEmpty()) {
            return NoOpSpanFactory.INSTANCE.createRootSpan(BATCH_SPAN, context);
        }
        Span batch = spanFactory.createRootSpan(BATCH_SPAN, context);
        batch.start(context);
        return batch;
    }

    @Override
    public Set<QualifiedName> supportedEvents() {
        return delegate.supportedEvents();
    }

    @Override
    public Object sequenceIdentifierFor(EventMessage event, ProcessingContext context) {
        return delegate.sequenceIdentifierFor(event, context);
    }

    @Override
    public boolean supportsReset() {
        return delegate.supportsReset();
    }

    @Override
    public MessageStream.Empty<Message> handle(ResetContext resetContext, ProcessingContext context) {
        return delegate.handle(resetContext, context);
    }

    @Override
    public MessageStream.Empty<Message> handle(ReplayStatusChanged statusChange, ProcessingContext context) {
        return delegate.handle(statusChange, context);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
        descriptor.describeProperty("spanFactory", spanFactory);
    }
}
