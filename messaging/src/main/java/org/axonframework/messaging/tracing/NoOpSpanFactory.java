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
 * A {@link SpanFactory} that produces spans which do nothing -- a null-object for tests and composition. Every
 * operation reduces to a single dispatch returning shared no-op instances.
 * <p>
 * This is <em>not</em> an off-switch: tracing is disabled by not registering a {@code SpanFactory} component at all,
 * in which case the tracing enhancers leave every component undecorated (zero overhead). There is no default
 * {@code SpanFactory}; a factory only exists when a tracing backend contributes one.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public final class NoOpSpanFactory implements SpanFactory {

    /**
     * The singleton {@link NoOpSpanFactory} instance.
     */
    public static final NoOpSpanFactory INSTANCE = new NoOpSpanFactory();

    private static final Span NO_OP_SPAN = new NoOpSpan();
    private static final SpanScope NO_OP_SCOPE = new NoOpSpanScope();

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

    private static final class NoOpSpan implements Span {

        @Override
        public SpanScope start() {
            return NO_OP_SCOPE;
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

        @Override
        public Span span() {
            return NO_OP_SPAN;
        }

        @Override
        public void close() {
            // No-op.
        }
    }
}
