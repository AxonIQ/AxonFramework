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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Test-only {@link SpanFactory} that produces spans which do nothing.
 */
final class NoOpSpanFactory implements SpanFactory {

    static final NoOpSpanFactory INSTANCE = new NoOpSpanFactory();

    private static final Span NO_OP_SPAN = new NoOpSpan();

    private NoOpSpanFactory() {
    }

    @Override
    public Span createDispatchSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        return NO_OP_SPAN;
    }

    @Override
    public Span createHandlerSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        return NO_OP_SPAN;
    }

    @Override
    public Span createContextParentHandlerSpan(String operationName, Message message,
                                               @Nullable ProcessingContext context) {
        return NO_OP_SPAN;
    }

    @Override
    public Span createLinkedHandlerSpan(String operationName, Message message, Message linkedMessage,
                                        @Nullable ProcessingContext context) {
        return NO_OP_SPAN;
    }

    @Override
    public Span createInternalSpan(String operationName, @Nullable ProcessingContext context) {
        return NO_OP_SPAN;
    }

    @Override
    public Span createRootSpan(String operationName, @Nullable ProcessingContext context) {
        return NO_OP_SPAN;
    }

    @Override
    public Span createDisconnectedHandlerSpan(String operationName, Message message,
                                              @Nullable ProcessingContext context) {
        return NO_OP_SPAN;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        // No-op - not required for testing
    }

    private static final class NoOpSpan implements Span {

        @Override
        public SpanScope start() {
            return new NoOpSpanScope();
        }

        @Override
        public Span addAttribute(String key, String value) {
            return this;
        }

        @Override
        public Span recordException(Throwable t) {
            return this;
        }

        @Override
        public <M extends Message> M propagateContext(M message) {
            return message;
        }
    }

    private static final class NoOpSpanScope implements SpanScope {

        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public Span span() {
            return NO_OP_SPAN;
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        @Override
        public <T> T within(Supplier<T> operation) {
            return operation.get();
        }
    }
}
