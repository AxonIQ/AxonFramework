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
import org.axonframework.messaging.tracing.SpanScope;
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
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Delegating {@link EventHandlingComponent} decorator that opens a per-event handler span around each event handled,
 * and -- for streaming-processor batches -- a single enclosing batch span.
 * <p>
 * On each {@link #handle(EventMessage, ProcessingContext)} a handler span (kind consumer) is opened
 * <b>branch-scoped</b> via {@link Span#branchStream(ProcessingContext, java.util.function.Function)}: its
 * {@link SpanScope} is carried on a context branch passed to the {@code delegate}, the handling window executes within
 * that scope, and the scope closes when this event's own result stream terminates -- not at batch end.
 * Carrying the scope on a branch, rather than writing it to the shared batch context, is what lets downstream children
 * (dispatch spans, asynchronous continuations, and provider-instrumented work) parent under their own event even when
 * a later event in the same batch has since started on the shared context.
 * <p>
 * The handler span's parent depends on the processor style: <b>subscribing</b> processors (no {@link Segment} on the
 * context) run inside the publisher's unit of work and always continue the publisher's trace; <b>streaming</b>
 * processors continue the publisher's trace only in distributed-in-same-trace mode for sufficiently fresh events. In
 * the default mode, a handler span is a child of the enclosing batch span and links back to the publisher. If batch
 * tracing is disabled, that handler span instead starts a new trace with the same publisher link.
 * <p>
 * The batch span, by contrast, is <b>context-lifetime</b>: it is opened lazily on the first event of a batch, only for
 * streaming processors, and only outside distributed-in-same-trace mode (in that mode per-event spans continue their
 * publishers' traces, so a batch root would dangle without meaningful children). It is bound to the shared batch
 * context's root via {@link Span#coverLifecycle(ProcessingContext)}, so it legitimately stays the active span there
 * for every lifecycle action (commit, etc.) that runs against that root. Subscribing processors get no separate batch
 * span, matching the established trace shape.
 * <p>
 * Every span this component creates -- the batch span and the per-event handler span -- carries the owning event
 * processor's name under the {@link #PROCESSOR_NAME_ATTRIBUTE} attribute (when the name is known), so spans of
 * different processors handling the same event type stay distinguishable in APM UIs.
 * <p>
 * This decorator is registered by {@code MessagingTracingConfigurationEnhancer}; it is never instantiated directly by
 * applications.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class TracingEventHandlingComponent implements EventHandlingComponent {

    /** Prefix for the per-event handler span ({@code "EventProcessor.process <name>"}). */
    public static final String PROCESS_SPAN = "EventProcessor.process";

    /** Name of the streaming-event-processor batch root span. */
    public static final String BATCH_SPAN = "StreamingEventProcessor.batch";

    /**
     * Attribute key carrying the owning event processor's name on every span this component creates
     * ({@code "axoniq.event-processor.name"}). Attached directly by this decorator -- like the entity-id attributes
     * on repository spans -- because the processor name is configuration, not message content, so no
     * {@link org.axonframework.messaging.tracing.SpanAttributesProvider} can contribute it.
     */
    public static final String PROCESSOR_NAME_ATTRIBUTE = "axoniq.event-processor.name";

    private static final Context.ResourceKey<Span> BATCH_SPAN_KEY =
            Context.ResourceKey.withLabel("org.axonframework.messaging.tracing.batchSpan");

    private final EventHandlingComponent delegate;
    private final SpanFactory spanFactory;
    private final @Nullable String processorName;
    private final boolean disableBatchTrace;
    private final boolean distributedInSameTrace;
    private final Duration distributedInSameTraceTimeLimit;

    /**
     * Initializes a tracing {@link EventHandlingComponent} with the default sub-toggles
     * ({@code disableBatchTrace=false}, {@code distributedInSameTrace=false},
     * {@code distributedInSameTraceTimeLimit=PT2M}) and no processor name.
     *
     * @param delegate    the event-handling component to delegate to
     * @param spanFactory the factory producing the tracing spans
     */
    public TracingEventHandlingComponent(EventHandlingComponent delegate, SpanFactory spanFactory) {
        this(delegate, spanFactory, null, false, false, Duration.ofMinutes(2));
    }

    /**
     * Initializes a tracing {@link EventHandlingComponent} wrapping the given {@code delegate}, obtaining spans from
     * the given {@code spanFactory}.
     *
     * @param delegate                        the event-handling component to delegate to
     * @param spanFactory                     the factory producing the tracing spans
     * @param processorName                   the name of the event processor owning the {@code delegate}, attached to
     *                                        every created span under {@link #PROCESSOR_NAME_ATTRIBUTE}; or
     *                                        {@code null} when unknown, in which case no attribute is attached
     * @param disableBatchTrace               when {@code true}, no enclosing batch span is opened for streaming-processor batches
     * @param distributedInSameTrace          when {@code true}, a streaming processor's handler span continues the
     *                                        publisher's trace and no batch span is opened; when {@code false}, the
     *                                        handler is parented to an enabled batch span and linked to its publisher,
     *                                        or starts a linked trace when batch tracing is disabled; subscribing
     *                                        processors always continue the publisher's trace
     * @param distributedInSameTraceTimeLimit how recent an event must be to continue the publisher's trace when {@code distributedInSameTrace} is {@code true}; older events (e.g. replays) start their own trace linked back to the publisher instead of stretching the publisher's long-finished trace
     */
    public TracingEventHandlingComponent(EventHandlingComponent delegate,
                                         SpanFactory spanFactory,
                                         @Nullable String processorName,
                                         boolean disableBatchTrace,
                                         boolean distributedInSameTrace,
                                         Duration distributedInSameTraceTimeLimit) {
        this.delegate = Objects.requireNonNull(delegate, "delegate may not be null");
        this.spanFactory = Objects.requireNonNull(spanFactory, "spanFactory may not be null");
        this.processorName = processorName;
        this.disableBatchTrace = disableBatchTrace;
        this.distributedInSameTrace = distributedInSameTrace;
        this.distributedInSameTraceTimeLimit =
                Objects.requireNonNull(distributedInSameTraceTimeLimit,
                                       "distributedInSameTraceTimeLimit may not be null");
    }

    @Override
    public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
        boolean streaming = Segment.fromContext(context).isPresent();

        // Open the batch span once per batch, on the first event of a streaming-processor batch -- unless disabled by
        // configuration or running in distributed-in-same-trace mode. In same-trace mode per-event spans continue
        // their publishers' traces, so a batch root would dangle without meaningful children. Note: the span is
        // started (which mutates the context via putResource) OUTSIDE a
        // computeResourceIfAbsent supplier -- mutating a ProcessingContext from within such a supplier is rejected as
        // a re-entrant ("recursive") update; get-then-put is deliberate.
        if (streaming && !disableBatchTrace && !distributedInSameTrace
                && context.getResource(BATCH_SPAN_KEY) == null) {
            Span batch = withProcessorName(spanFactory.createRootSpan(BATCH_SPAN, context));
            batch.coverLifecycle(context);
            context.putResource(BATCH_SPAN_KEY, batch);
        }

        // The handler span is branch-scoped. A subscribing or same-trace handler uses the propagated publisher as its
        // parent. A handler inside a traced streaming batch uses the batch as its parent and links to the publisher.
        // Without either relationship it starts a new trace linked to the publisher. Span#branchStream carries the
        // selected span on a context branch passed to the delegate, so the delegate's children read back this exact
        // scope, and closes it on this event's own stream termination, never at batch end.
        //
        // Subscribing processors (no Segment) run inside the publisher's unit of work and therefore always continue
        // the publisher's trace, regardless of the distributedInSameTrace toggle, matching the established shape.
        String spanName = PROCESS_SPAN + " " + event.type().qualifiedName().name();
        Span handlerSpan;
        if (!streaming || continuesPublisherTrace(event)) {
            handlerSpan = spanFactory.createHandlerSpan(spanName, event, context);
        } else if (context.getResource(BATCH_SPAN_KEY) != null) {
            handlerSpan = spanFactory.createContextParentHandlerSpan(spanName, event, context);
        } else {
            handlerSpan = spanFactory.createDisconnectedHandlerSpan(spanName, event, context);
        }
        return withProcessorName(handlerSpan).branchStream(context, spanned -> delegate.handle(event, spanned))
                                             .ignoreEntries();
    }

    /**
     * Attaches the owning processor's name under {@link #PROCESSOR_NAME_ATTRIBUTE} when it is known; returns the
     * span unchanged otherwise.
     */
    private Span withProcessorName(Span span) {
        return processorName == null ? span : span.addAttribute(PROCESSOR_NAME_ATTRIBUTE, processorName);
    }

    /**
     * Decides whether the per-event handler span continues the publisher's trace: only when
     * {@code distributedInSameTrace} is enabled AND the event is younger than
     * {@code distributedInSameTraceTimeLimit}. Stale events -- typically replays -- get a disconnected span (new trace
     * linked back to the publisher) so they do not stretch a long-finished trace.
     */
    private boolean continuesPublisherTrace(EventMessage event) {
        return distributedInSameTrace
                && !event.timestamp().isBefore(Instant.now().minus(distributedInSameTraceTimeLimit));
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
