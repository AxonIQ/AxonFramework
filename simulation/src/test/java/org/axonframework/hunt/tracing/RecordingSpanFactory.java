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

package org.axonframework.hunt.tracing;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.SpanScope;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Minimal thread-safe recording {@link SpanFactory} for the hunt suite. Each created span records its kind, name,
 * parent (the context's active {@link SpanScope} at creation, per the {@link SpanFactory} parent-resolution contract),
 * link (the tracing context propagated in message metadata), start/end state, recorded exception and scope entries.
 * <p>
 * Cross-process propagation is real: {@link Span#propagateContext(Message)} writes the span id into the message's
 * metadata under {@link #METADATA_KEY}, so a span created for an event read back from a store can still resolve its
 * publisher -- exactly what a real tracing provider does with W3C trace context.
 * <p>
 * {@link SpanScope#within(Supplier)} maintains a per-thread current-span {@code ThreadLocal}, mimicking an
 * OpenTelemetry-style provider, so tests can detect scope state leaking onto foreign threads.
 */
public final class RecordingSpanFactory implements SpanFactory {

    public static final String METADATA_KEY = "hunt.trace.parent";

    public enum Kind {DISPATCH, HANDLER, CONTEXT_PARENT_HANDLER, LINKED_HANDLER, INTERNAL, ROOT, DISCONNECTED_HANDLER}

    private final AtomicLong ids = new AtomicLong();
    private final List<RecordedSpan> spans = new CopyOnWriteArrayList<>();
    private final ThreadLocal<@Nullable RecordedSpan> current = new ThreadLocal<>();

    @Override
    public Span createDispatchSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        return record(Kind.DISPATCH, operationName, message, contextualParent(context), null);
    }

    @Override
    public Span createHandlerSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        Long propagated = propagated(message);
        return record(Kind.HANDLER, operationName, message,
                      propagated != null ? propagated : contextualParent(context), null);
    }

    @Override
    public Span createContextParentHandlerSpan(String operationName, Message message,
                                               @Nullable ProcessingContext context) {
        return record(Kind.CONTEXT_PARENT_HANDLER, operationName, message,
                      contextualParent(context), propagated(message));
    }

    @Override
    public Span createLinkedHandlerSpan(String operationName, Message message, Message linkedMessage,
                                        @Nullable ProcessingContext context) {
        Long propagated = propagated(message);
        return record(Kind.LINKED_HANDLER, operationName, message,
                      propagated != null ? propagated : contextualParent(context), propagated(linkedMessage));
    }

    @Override
    public Span createInternalSpan(String operationName, @Nullable ProcessingContext context) {
        return record(Kind.INTERNAL, operationName, null, contextualParent(context), null);
    }

    @Override
    public Span createRootSpan(String operationName, @Nullable ProcessingContext context) {
        return record(Kind.ROOT, operationName, null, null, contextualParent(context));
    }

    @Override
    public Span createDisconnectedHandlerSpan(String operationName, Message message,
                                              @Nullable ProcessingContext context) {
        return record(Kind.DISCONNECTED_HANDLER, operationName, message, null, propagated(message));
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        // Nothing to describe for a test recorder.
    }

    private RecordedSpan record(Kind kind, String name, @Nullable Message message,
                                @Nullable Long parentId, @Nullable Long linkId) {
        RecordedSpan span = new RecordedSpan(ids.incrementAndGet(), kind, name,
                                             message == null ? null : message.identifier(), parentId, linkId);
        spans.add(span);
        return span;
    }

    private @Nullable Long contextualParent(@Nullable ProcessingContext context) {
        if (context == null) {
            return null;
        }
        SpanScope active = SpanScope.fromContext(context);
        return active != null && active.span() instanceof RecordedSpan recorded ? recorded.id() : null;
    }

    private static @Nullable Long propagated(Message message) {
        String value = message.metadata().get(METADATA_KEY);
        return value == null ? null : Long.valueOf(value);
    }

    // -- queries ------------------------------------------------------------------------------------------------

    public List<RecordedSpan> all() {
        return List.copyOf(spans);
    }

    public List<RecordedSpan> byPrefix(String namePrefix) {
        return spans.stream().filter(s -> s.name().startsWith(namePrefix)).toList();
    }

    /** Returns the single span whose name starts with the given prefix, failing when there is not exactly one. */
    public RecordedSpan onlyByPrefix(String namePrefix) {
        List<RecordedSpan> matches = byPrefix(namePrefix);
        if (matches.size() != 1) {
            throw new AssertionError("Expected exactly one span with prefix '" + namePrefix + "' but found "
                                             + matches.size() + ". All spans:\n" + render());
        }
        return matches.get(0);
    }

    /** Spans that were started and never ended. */
    public List<RecordedSpan> openSpans() {
        return spans.stream().filter(s -> s.started() && !s.ended()).toList();
    }

    /** The span whose scope the calling thread is currently within, or {@code null}. */
    public @Nullable RecordedSpan currentSpan() {
        return current.get();
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (RecordedSpan span : spans) {
            sb.append(span).append('\n');
        }
        return sb.toString();
    }

    public final class RecordedSpan implements Span {

        private final long id;
        private final Kind kind;
        private final String name;
        private final @Nullable String messageId;
        private final @Nullable Long parentId;
        private final @Nullable Long linkId;
        private final Map<String, String> attributes = new ConcurrentHashMap<>();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicInteger scopeEntries = new AtomicInteger();
        private volatile @Nullable Throwable error;

        private RecordedSpan(long id, Kind kind, String name, @Nullable String messageId,
                             @Nullable Long parentId, @Nullable Long linkId) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.messageId = messageId;
            this.parentId = parentId;
            this.linkId = linkId;
        }

        public long id() {
            return id;
        }

        public Kind kind() {
            return kind;
        }

        public String name() {
            return name;
        }

        public @Nullable String messageId() {
            return messageId;
        }

        public @Nullable Long parentId() {
            return parentId;
        }

        public @Nullable Long linkId() {
            return linkId;
        }

        public boolean started() {
            return started.get();
        }

        public boolean ended() {
            return ended.get();
        }

        public @Nullable Throwable error() {
            return error;
        }

        public int scopeEntries() {
            return scopeEntries.get();
        }

        public Map<String, String> attributes() {
            return Map.copyOf(attributes);
        }

        @Override
        public SpanScope start() {
            started.set(true);
            return new RecordedScope(this);
        }

        @Override
        public Span addAttribute(String key, String value) {
            attributes.put(key, value);
            return this;
        }

        @Override
        public Span recordException(Throwable t) {
            this.error = t;
            return this;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <M extends Message> M propagateContext(M message) {
            return (M) message.andMetadata(Map.of(METADATA_KEY, Long.toString(id)));
        }

        @Override
        public String toString() {
            return "span#" + id + "[" + kind + " '" + name + "' parent=" + parentId + " link=" + linkId
                    + " started=" + started.get() + " ended=" + ended.get()
                    + (error == null ? "" : " error=" + error.getClass().getSimpleName()) + "]";
        }
    }

    private final class RecordedScope implements SpanScope {

        private final RecordedSpan span;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RecordedScope(RecordedSpan span) {
            this.span = span;
        }

        @Override
        public Span span() {
            return span;
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                span.ended.set(true);
            }
        }

        @Override
        public <T> T within(Supplier<T> operation) {
            span.scopeEntries.incrementAndGet();
            RecordedSpan previous = current.get();
            current.set(span);
            try {
                return operation.get();
            } finally {
                if (previous == null) {
                    current.remove();
                } else {
                    current.set(previous);
                }
            }
        }
    }
}
