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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link SpanFactory} that composes several delegate factories, so a single span operation is reported to all of
 * them. A typical use is combining the OpenTelemetry factory with {@link LoggingSpanFactory} during development.
 * <p>
 * Each created {@link Span} fans out to one span per delegate; starting, adding attributes, recording exceptions and
 * closing the scope are applied to all of them. {@link #propagateContext(Message)} applies every delegate's
 * propagation in turn.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public final class MultiSpanFactory implements SpanFactory {

    private final List<SpanFactory> delegates;

    /**
     * Initializes a {@link MultiSpanFactory} delegating to the given factories, in order.
     *
     * @param delegates the factories to compose; must not be {@code null} or empty
     */
    public MultiSpanFactory(List<SpanFactory> delegates) {
        Objects.requireNonNull(delegates, "delegates may not be null");
        if (delegates.isEmpty()) {
            throw new IllegalArgumentException("delegates may not be empty");
        }
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public Span createDispatchSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        return fanOut(factory -> factory.createDispatchSpan(operationName, message, context));
    }

    @Override
    public Span createHandlerSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        return fanOut(factory -> factory.createHandlerSpan(operationName, message, context));
    }

    @Override
    public Span createLinkedHandlerSpan(String operationName, Message message, Message linkedMessage,
                                        @Nullable ProcessingContext context) {
        return fanOut(factory -> factory.createLinkedHandlerSpan(operationName, message, linkedMessage, context));
    }

    @Override
    public Span createInternalSpan(String operationName, @Nullable ProcessingContext context) {
        return fanOut(factory -> factory.createInternalSpan(operationName, context));
    }

    @Override
    public Span createRootSpan(String operationName, @Nullable ProcessingContext context) {
        return fanOut(factory -> factory.createRootSpan(operationName, context));
    }

    @Override
    public Span createDisconnectedHandlerSpan(String operationName, Message message,
                                              @Nullable ProcessingContext context) {
        return fanOut(factory -> factory.createDisconnectedHandlerSpan(operationName, message, context));
    }

    private Span fanOut(java.util.function.Function<SpanFactory, Span> spanCreator) {
        List<Span> spans = new ArrayList<>(delegates.size());
        for (SpanFactory delegate : delegates) {
            spans.add(spanCreator.apply(delegate));
        }
        return new MultiSpan(spans);
    }

    private static final class MultiSpan implements Span {

        private final List<Span> spans;

        private MultiSpan(List<Span> spans) {
            this.spans = spans;
        }

        @Override
        public SpanScope start() {
            List<SpanScope> scopes = new ArrayList<>(spans.size());
            for (Span span : spans) {
                scopes.add(span.start());
            }
            return new MultiSpanScope(this, scopes);
        }

        @Override
        public Span addAttribute(String key, String value) {
            spans.forEach(span -> span.addAttribute(key, value));
            return this;
        }

        @Override
        public Span recordException(Throwable t) {
            spans.forEach(span -> span.recordException(t));
            return this;
        }

        @Override
        public <M extends Message> M propagateContext(M message) {
            M result = message;
            for (Span span : spans) {
                result = span.propagateContext(result);
            }
            return result;
        }
    }

    private static final class MultiSpanScope implements SpanScope {

        private final Span span;
        private final List<SpanScope> scopes;

        private MultiSpanScope(Span span, List<SpanScope> scopes) {
            this.span = span;
            this.scopes = scopes;
        }

        @Override
        public Span span() {
            return span;
        }

        @Override
        public void close() {
            scopes.forEach(SpanScope::close);
        }
    }
}
