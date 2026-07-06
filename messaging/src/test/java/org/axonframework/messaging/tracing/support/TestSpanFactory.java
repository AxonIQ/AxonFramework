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

package org.axonframework.messaging.tracing.support;

import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.SpanScope;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recording {@link SpanFactory} test double. Each created span records whether it was started, ended, errored, the
 * attributes set on it, and the message it was created for. Tests assert on the recorded state through the
 * {@code verify*} methods rather than mocking. Ported from Axon Framework 4's {@code TestSpanFactory} and adapted to
 * the consolidated Axon Framework 5 {@link SpanFactory} API.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public class TestSpanFactory implements SpanFactory {

    private final Logger logger = LoggerFactory.getLogger(TestSpanFactory.class);

    private final Deque<TestSpan> activeSpans = new ArrayDeque<>();
    private final List<TestSpan> createdSpans = new CopyOnWriteArrayList<>();
    private final List<SpanAttributesProvider> spanAttributesProviders = new CopyOnWriteArrayList<>();
    private final Map<Message, TestSpan> propagatedContexts = new IdentityHashMap<>();

    /**
     * The kind of span, mapped from the {@link SpanFactory} factory method that produced it.
     */
    public enum TestSpanType {
        DISPATCH,
        HANDLER,
        LINKED_HANDLER,
        INTERNAL,
        ROOT,
        DISCONNECTED_HANDLER
    }

    /**
     * Resets this factory to a pristine state.
     */
    public void reset() {
        activeSpans.clear();
        createdSpans.clear();
        propagatedContexts.clear();
    }

    @Override
    public Span createDispatchSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        return recordMessageSpan(new TestSpan(TestSpanType.DISPATCH, operationName, message), message, context);
    }

    @Override
    public Span createHandlerSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        return recordMessageSpan(new TestSpan(TestSpanType.HANDLER, operationName, message), message, context);
    }

    @Override
    public Span createLinkedHandlerSpan(String operationName, Message message, Message linkedMessage,
                                        @Nullable ProcessingContext context) {
        return recordMessageSpan(new TestSpan(TestSpanType.LINKED_HANDLER, operationName, message), message, context);
    }

    @Override
    public Span createInternalSpan(String operationName, @Nullable ProcessingContext context) {
        return record(new TestSpan(TestSpanType.INTERNAL, operationName, null));
    }

    @Override
    public Span createRootSpan(String operationName, @Nullable ProcessingContext context) {
        return record(new TestSpan(TestSpanType.ROOT, operationName, null));
    }

    @Override
    public Span createDisconnectedHandlerSpan(String operationName, Message message,
                                              @Nullable ProcessingContext context) {
        return recordMessageSpan(new TestSpan(TestSpanType.DISCONNECTED_HANDLER, operationName, message), message, context);
    }

    /**
     * Registers a {@link SpanAttributesProvider} contributing attributes to every span this recorder produces. Kept
     * as a mutable test convenience -- production factories receive their providers at construction time.
     */
    public void registerAttributesProvider(SpanAttributesProvider provider) {
        spanAttributesProviders.add(provider);
    }

    private TestSpan record(TestSpan span) {
        createdSpans.add(span);
        return span;
    }

    private TestSpan recordMessageSpan(TestSpan span, Message message, @Nullable ProcessingContext context) {
        spanAttributesProviders.forEach(provider -> provider.provideForMessage(message, context)
                                                            .forEach(span::addAttribute));
        return record(span);
    }

    /**
     * Verifies a span with the given name was created and started but not yet ended.
     *
     * @param name the span name
     */
    public void verifySpanActive(String name) {
        assertThat(findSpan(name, span -> span.started && !span.ended))
                .withFailMessage(() -> errorMessage(name))
                .isPresent();
    }

    /**
     * Verifies a span with the given name was created, started, and ended.
     *
     * @param name the span name
     */
    public void verifySpanCompleted(String name) {
        assertThat(findSpan(name, span -> span.started && span.ended))
                .withFailMessage(() -> errorMessage(name))
                .isPresent();
    }

    /**
     * Verifies a span with the given name was created for the given message, started, and ended.
     *
     * @param name    the span name
     * @param message the message the span should have been created for
     */
    public void verifySpanCompleted(String name, Message message) {
        assertThat(findSpan(name, message, span -> span.started && span.ended))
                .withFailMessage(() -> errorMessage(name))
                .isPresent();
    }

    /**
     * Verifies no span with the given name was started.
     *
     * @param name the span name
     */
    public void verifyNotStarted(String name) {
        assertThat(findSpan(name, span -> span.started))
                .withFailMessage(() -> errorMessage(name))
                .isNotPresent();
    }

    /**
     * Verifies no span with the given name was created at all.
     *
     * @param name the span name
     */
    public void verifyNoSpan(String name) {
        assertThat(findSpan(name, span -> true)).isNotPresent();
    }

    /**
     * Verifies a span with the given name is of the given kind.
     *
     * @param name the span name
     * @param type the expected kind
     */
    public void verifySpanHasType(String name, TestSpanType type) {
        assertThat(findSpan(name, span -> true).map(span -> span.type)).contains(type);
    }

    /**
     * Verifies a span with the given name carries the given attribute value.
     *
     * @param name  the span name
     * @param key   the attribute key
     * @param value the expected attribute value
     */
    public void verifySpanHasAttributeValue(String name, String key, String value) {
        assertThat(findSpan(name, span -> value.equals(span.attributes.get(key))))
                .withFailMessage(() -> errorMessage(name))
                .isPresent();
    }

    /**
     * Verifies a span with the given name exists and does not carry an attribute under the given key.
     *
     * @param name the span name
     * @param key  the attribute key that must be absent
     */
    public void verifySpanHasNoAttribute(String name, String key) {
        assertThat(findSpan(name, span -> true))
                .withFailMessage(() -> errorMessage(name))
                .hasValueSatisfying(span -> assertThat(span.attributes).doesNotContainKey(key));
    }

    /**
     * Verifies a span with the given name recorded an exception of the given type.
     *
     * @param name           the span name
     * @param exceptionClass the expected exception type
     */
    public void verifySpanHasException(String name, Class<? extends Throwable> exceptionClass) {
        assertThat(findSpan(name, span -> true).map(span -> span.exception))
                .get()
                .isInstanceOf(exceptionClass);
    }

    /**
     * Verifies the given message had the active context of the named span propagated onto it.
     *
     * @param name    the span name
     * @param message the message the context should have been propagated onto
     */
    public void verifySpanPropagated(String name, Message message) {
        assertThat(createdSpans)
                .withFailMessage(() -> errorMessage(name))
                .anyMatch(span -> span.name.equals(name) && propagatedContexts.get(message) == span);
    }

    private Optional<TestSpan> findSpan(String name, Predicate<TestSpan> filter) {
        return createdSpans.stream()
                            .filter(span -> span.name.equals(name))
                            .filter(filter)
                            .findFirst();
    }

    private Optional<TestSpan> findSpan(String name, Message message, Predicate<TestSpan> filter) {
        return findSpan(name, filter.and(span -> span.message != null
                && span.message.identifier().equals(message.identifier())));
    }

    private String errorMessage(String name) {
        return String.format("No span matching name '%s'. Recorded spans:%n%s",
                             name,
                             createdSpans.stream().map(TestSpan::toString).collect(Collectors.joining(System.lineSeparator())));
    }

    /**
     * A recording {@link Span} produced by {@link TestSpanFactory}.
     */
    public class TestSpan implements Span {

        private final TestSpanType type;
        private final String name;
        private final @Nullable Message message;
        private final Map<String, String> attributes = new HashMap<>();
        private boolean started;
        private boolean ended;
        private @Nullable Throwable exception;

        private TestSpan(TestSpanType type, String name, @Nullable Message message) {
            this.type = type;
            this.name = name;
            this.message = message;
        }

        @Override
        public SpanScope start() {
            started = true;
            synchronized (activeSpans) {
                activeSpans.addFirst(this);
            }
            logger.debug("+ {}", name);
            return new TestSpanScope(this);
        }

        @Override
        public Span addAttribute(String key, String value) {
            attributes.put(key, value);
            return this;
        }

        @Override
        public Span recordException(Throwable t) {
            this.exception = t;
            logger.debug("Recorded exception for span {}", name, t);
            return this;
        }

        @Override
        public <M extends Message> M propagateContext(M message) {
            propagatedContexts.put(message, this);
            return message;
        }

        private void close() {
            ended = true;
            synchronized (activeSpans) {
                activeSpans.remove(this);
            }
            logger.debug("- {}", name);
        }

        @Override
        public String toString() {
            return "TestSpan{type=" + type + ", name='" + name + '\'' + ", started=" + started
                    + ", ended=" + ended + ", exception=" + exception + ", attributes=" + attributes + '}';
        }
    }

    private final class TestSpanScope implements SpanScope {

        private final TestSpan span;

        private TestSpanScope(TestSpan span) {
            this.span = span;
        }

        @Override
        public Span span() {
            return span;
        }

        @Override
        public void close() {
            span.close();
        }
    }
}
