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

import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

/**
 * The sole public abstraction for creating tracing {@link Span spans} in Axon Framework.
 * <p>
 * A single {@code SpanFactory} is registered against the framework's {@code ComponentRegistry}; the per-concern
 * tracing modules then wrap components with delegating tracing decorators that obtain their spans from this factory.
 * There is intentionally no per-bus or per-component {@code SpanFactory} interface -- the per-component span shapes
 * (names, kinds, attributes, cross-process metadata propagation) are implementation details of those internal
 * decorators.
 * <p>
 * <b>Parent resolution.</b> Parents are resolved from (1) the propagated context carried in a {@link Message}'s
 * metadata (cross-thread / cross-process) and (2) the active span recorded on the supplied {@link ProcessingContext}
 * (in-process nesting; see {@link Span#start()}). Those two carriers are authoritative for framework-internal
 * parenting. When neither yields a parent, an implementation MAY fall back to its tracing provider's ambient trace
 * context before starting a new trace (a root): that fallback lets framework spans join a trace opened by an
 * externally-instrumented caller (an HTTP controller, a scheduled job) and covers the framework edges where no
 * {@code ProcessingContext} exists at span-creation time. To force a new trace regardless of any active span, use
 * {@link #createRootSpan(String, ProcessingContext)}.
 * <p>
 * <b>Span names are evaluated eagerly.</b> Every factory method takes the operation name as a plain {@code String} --
 * matching OpenTelemetry's own eager API ({@code Tracer.spanBuilder(String)}) -- rather than a lazy
 * {@code Supplier<String>}. Callers building <em>expensive</em> names (e.g. reflective method signatures) must
 * therefore decide that a span will actually be created <em>before</em> computing the name, and build it only on the
 * span-creating branch. Decorator authors (including extension authors instrumenting their own components) should
 * not install a tracing decorator when no factory is configured, so the un-traced path carries no name-building cost
 * whatsoever.
 * <p>
 * Fan-out to multiple tracing destinations is a concern of the {@code SpanFactory} implementation's export layer,
 * below this abstraction. Tracing is disabled by <em>not registering</em> a {@code SpanFactory} component at all --
 * component decorators are then not installed.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public interface SpanFactory extends DescribableComponent {

    /**
     * Creates a {@link Span} for an outbound (dispatch / producer) operation on the given {@link Message}. The parent
     * is the active span on {@code context} (when present), so a message dispatched from within another traced
     * operation nests under it; otherwise resolution continues per the class-level parent-resolution notes (the
     * context propagated in {@code message}'s metadata, then the implementation's optional ambient fallback, then a
     * new root). The {@code context}, when non-{@code null}, is also forwarded to every
     * {@link SpanAttributesProvider} the implementation was constructed with.
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
     * present, the active span on {@code context}; when neither is present, the implementation's optional ambient
     * fallback applies before a new root (see the class-level parent-resolution notes).
     *
     * @param operationName the span name
     * @param message       the message being handled
     * @param context       the active processing context, or {@code null} when none is available
     * @return the created span (not yet started)
     */
    Span createHandlerSpan(String operationName, Message message, @Nullable ProcessingContext context);

    /**
     * Creates a {@link Span} for an inbound (handler / consumer) operation that runs inside an independently traced
     * processing context. The parent is the active span on {@code context}; the tracing context propagated in
     * {@code message}'s metadata is attached as a span link instead of replacing that parent. This keeps an enclosing
     * operation, such as a streaming-event-processor batch, as the structural parent while preserving navigation to
     * the producer that created the handled message.
     * <p>
     * Implementations MUST prefer the active span on {@code context} over the propagated message context. When no
     * active context span is available, the implementation's optional ambient fallback applies before a new root.
     * Implementations MUST independently extract the propagated context from {@code message} and attach it as a link;
     * when no link can be extracted, the span is still created and this method never throws.
     *
     * @param operationName the span name
     * @param message       the message being handled -- its metadata supplies the link target
     * @param context       the processing context supplying the structural parent, or {@code null} when unavailable
     * @return the created span (not yet started)
     */
    Span createContextParentHandlerSpan(String operationName, Message message,
                                        @Nullable ProcessingContext context);

    /**
     * Creates a {@link Span} for an inbound (handler / consumer) operation on the given {@link Message}, with an
     * additional link to {@code linkedMessage}'s span context. The link expresses a relationship between traces
     * without changing the span's parent; tracing backends typically render it as navigation between the linked
     * traces. Implementations MUST extract the propagated context from {@code linkedMessage}'s metadata and attach it
     * as a span link; when no link can be extracted the span is still created without the link, and this method never
     * throws. Parent resolution is as in {@link #createHandlerSpan(String, Message, ProcessingContext)}.
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
     * (for example a handler span); otherwise the implementation's optional ambient fallback applies before a new
     * root (see the class-level parent-resolution notes). Non-message attributes are attached by the calling
     * decorator via {@link Span#addAttribute(String, String)}.
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
     * This is the "distributed-in-different-trace" handling mode. Use it when joining the publisher's trace would
     * either flood it (long-running consumers, batch processors) or cross trust / lifecycle boundaries, while keeping
     * the consumer's new trace navigable back to the producer through the link.
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
     * as a parent), preserving the relationship to the operation that triggered it without creating a parent-of
     * relationship to that operation.
     *
     * @param operationName the span name
     * @param context       the processing context the root should become the active span of (and link back to), or
     *                      {@code null}
     * @return the created root span (not yet started)
     */
    Span createRootSpan(String operationName, @Nullable ProcessingContext context);

}
