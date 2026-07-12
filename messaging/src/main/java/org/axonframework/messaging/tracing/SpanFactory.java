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

package org.axonframework.messaging.tracing;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

/**
 * The sole public abstraction for creating tracing {@link Span spans} in Axon Framework.
 * <p>
 * A single {@code SpanFactory} is registered against the framework's {@code ComponentRegistry}; the per-concern
 * tracing modules then wrap messaging, modelling and event-sourcing components with delegating tracing decorators
 * that obtain their spans from this factory. There is intentionally no per-bus or per-component {@code SpanFactory}
 * interface -- the per-component span shapes (names, kinds, attributes, cross-process metadata propagation) are
 * implementation details of those internal decorators.
 * <p>
 * <b>No {@code ThreadLocal}.</b> A span's parent is never read from a thread-bound "current span". Parents are resolved
 * from (1) the propagated context carried in a {@link Message}'s metadata (cross-thread / cross-process) and (2) the
 * active span recorded on the supplied {@link ProcessingContext} (in-process nesting; see {@link Span#start()}). When
 * neither yields a parent, the span starts a new trace (a root). To force a new trace regardless of any active span,
 * use {@link #createRootSpan(String)}.
 * <p>
 * <b>Span names are evaluated eagerly.</b> Every factory method takes the operation name as a plain {@code String} --
 * matching OpenTelemetry's own eager API ({@code Tracer.spanBuilder(String)}) -- rather than a lazy
 * {@code Supplier<String>}. Callers building <em>expensive</em> names (e.g. reflective method signatures) must
 * therefore decide that a span will actually be created <em>before</em> computing the name, and build it only on the
 * span-creating branch. Decorator authors (including extension authors instrumenting their own components) should
 * additionally prefer not installing a tracing decorator at all when the configured factory is {@link NoOpSpanFactory}
 * or absent, so the un-traced path carries no name-building cost whatsoever.
 * <p>
 * Fan-out to multiple tracing destinations is configured through Micrometer Tracing and the exporters behind it, below
 * the {@code SpanFactory}. Tracing is disabled by <em>not registering</em> a {@code SpanFactory} component at all --
 * the tracing enhancers then leave every component undecorated (zero overhead). {@link NoOpSpanFactory} is a
 * null-object for tests, not an off-switch.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public interface SpanFactory {

    /**
     * Creates a {@link Span} for an outbound (dispatch / producer) operation on the given {@link Message}. The parent
     * is the active span on {@code context} (when present), so a message dispatched from within another traced
     * operation nests under it; otherwise a root span. The {@code context}, when non-{@code null}, is also forwarded
     * to every {@link SpanAttributesProvider} the implementation was constructed with.
     *
     * @param operationName the span name
     * @param message       the message the operation acts on
     * @param context       the active processing context, or {@code null} when none is available
     * @return the created span (not yet started)
     */
    Span createDispatchSpan(String operationName, Message message, @Nullable ProcessingContext context);

    /**
     * Creates a {@link Span} for an inbound (handler / consumer) operation on the given {@link Message}. The parent is
     * the tracing context propagated in {@code message}'s metadata (cross-thread / cross-process); when none is
     * present, the active span on {@code context}; when neither is present, a root span. Never reads a thread-bound
     * current span.
     *
     * @param operationName the span name
     * @param message       the message being handled
     * @param context       the active processing context, or {@code null} when none is available
     * @return the created span (not yet started)
     */
    Span createHandlerSpan(String operationName, Message message, @Nullable ProcessingContext context);

    /**
     * Creates a {@link Span} for an inbound (handler / consumer) operation on the given {@link Message}, with an
     * additional link to {@code linkedMessage}'s span context. The link is rendered by APM UIs as a clickable
     * cross-trace navigation (not a parent-of relationship, not an attribute). Implementations MUST extract the
     * propagated context from {@code linkedMessage}'s metadata and attach it as a span link; when no link can be
     * extracted the span is still created without the link, and this method never throws. Parent resolution is as in
     * {@link #createHandlerSpan(String, Message, ProcessingContext)}.
     *
     * @param operationName the span name
     * @param message       the message being handled
     * @param linkedMessage the message whose span context is linked to
     * @param context       the active processing context, or {@code null} when none is available
     * @return the created span (not yet started)
     */
    Span createLinkedHandlerSpan(String operationName, Message message, Message linkedMessage,
                                 @Nullable ProcessingContext context);

    /**
     * Creates a {@link Span} for an internal operation that is not directly tied to a {@link Message}. The parent is
     * the active span on {@code context} (when present), so the internal span nests under the operation that opened it
     * (for example a handler span); otherwise a root span. Non-message attributes are attached by the calling decorator
     * via {@link Span#addAttribute(String, String)}.
     *
     * @param operationName the span name
     * @param context       the active processing context, or {@code null} when none is available
     * @return the created span (not yet started)
     */
    Span createInternalSpan(String operationName, @Nullable ProcessingContext context);

    /**
     * Creates a {@link Span} for an inbound (handler / consumer) operation that should start a <em>new trace</em>
     * (root) yet remain navigable to the producing trace through a span <em>link</em>. The link target is the tracing
     * context propagated in {@code message}'s metadata.
     * <p>
     * This is the "distributed-in-different-trace" handling mode (AF4's
     * {@code createHandlerSpan(message, isChildTrace=false)} / event-processor
     * {@code distributedInSameTrace=false}). Use it when joining the publisher's trace would either flood it (long-
     * running consumers, batch processors) or cross trust / lifecycle boundaries, but you still want the APM UI to
     * surface a clickable link from the producer's span to the consumer's new trace.
     * <p>
     * Implementations MUST start a new trace (no parent-of relationship to the producer), extract the producer's
     * context from {@code message}'s metadata and attach it as a span link. When no link can be extracted (e.g. no
     * tracing context on the message), the span is still created without a link and this method never throws.
     *
     * @param operationName the span name
     * @param message       the message being handled -- its metadata supplies the link target
     * @param context       the active processing context, or {@code null} when none is available
     * @return the created span (not yet started)
     */
    Span createDisconnectedHandlerSpan(String operationName, Message message, @Nullable ProcessingContext context);

    /**
     * Creates a {@link Span} that always starts a new trace (a root), ignoring any active span when resolving its own
     * parent. Use this for operations that legitimately begin their own trace and must not attach to a stale or
     * unrelated active span -- for example an event-processing batch boundary or an out-of-band snapshot operation
     * running on a pooled thread. When {@code context} is non-{@code null}, starting the span still records it as that
     * context's active span, so spans created next with that context nest under this root.
     * <p>
     * When {@code context} carries an active span, implementations MUST attach that span as a span <em>link</em> (not
     * as a parent), so the new trace stays navigable in APM UIs back to the operation that triggered it. This is the
     * no-{@code ThreadLocal} equivalent of the originating-span link a thread-bound "current span" would have provided.
     *
     * @param operationName the span name
     * @param context       the processing context the root should become the active span of (and link back to), or
     *                      {@code null}
     * @return the created root span (not yet started)
     */
    Span createRootSpan(String operationName, @Nullable ProcessingContext context);

}
