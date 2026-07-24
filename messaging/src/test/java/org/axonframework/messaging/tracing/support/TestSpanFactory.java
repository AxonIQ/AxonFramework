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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recording {@link SpanFactory} test double. Each created span records whether it was started, ended, errored, the
 * attributes set on it, and the message it was created for. Tests assert on the recorded state through the
 * {@code verify*} methods rather than mocking. Ported from Axon Framework 4's {@code TestSpanFactory} and adapted to
 * the consolidated {@link SpanFactory} API.
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
        CONTEXT_PARENT_HANDLER,
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
        return recordMessageSpan(TestSpanType.DISPATCH, operationName, message, context);
    }

    @Override
    public Span createHandlerSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        return recordMessageSpan(TestSpanType.HANDLER, operationName, message, context);
    }

    @Override
    public Span createContextParentHandlerSpan(String operationName, Message message,
                                               @Nullable ProcessingContext context) {
        TestSpan span = recordMessageSpan(TestSpanType.CONTEXT_PARENT_HANDLER, operationName, message, context);
        span.link = propagatedContexts.get(message);
        return span;
    }

    @Override
    public Span createLinkedHandlerSpan(String operationName, Message message, Message linkedMessage,
                                        @Nullable ProcessingContext context) {
        TestSpan span = recordMessageSpan(TestSpanType.LINKED_HANDLER, operationName, message, context);
        TestSpan propagatedParent = propagatedContexts.get(message);
        if (propagatedParent != null) {
            span.contextualParent = propagatedParent;
        }
        span.link = propagatedContexts.get(linkedMessage);
        return span;
    }

    @Override
    public Span createInternalSpan(String operationName, @Nullable ProcessingContext context) {
        return record(new TestSpan(TestSpanType.INTERNAL, operationName, null), context);
    }

    @Override
    public Span createRootSpan(String operationName, @Nullable ProcessingContext context) {
        return record(new TestSpan(TestSpanType.ROOT, operationName, null), context);
    }

    @Override
    public Span createDisconnectedHandlerSpan(String operationName, Message message,
                                              @Nullable ProcessingContext context) {
        return recordMessageSpan(TestSpanType.DISCONNECTED_HANDLER, operationName, message, context);
    }

    /**
     * Registers a {@link SpanAttributesProvider} contributing attributes to every span this recorder produces. Kept
     * as a mutable test convenience -- production factories receive their providers at construction time.
     */
    public void registerAttributesProvider(SpanAttributesProvider provider) {
        spanAttributesProviders.add(provider);
    }

    /**
     * Records the given {@code span}, capturing the context's active {@link SpanScope} (if any) at creation time, so
     * tests can assert on the exact parent edge a real {@link SpanFactory} would have resolved. For a
     * {@link TestSpanType#ROOT} or {@link TestSpanType#DISCONNECTED_HANDLER} span, the active span at creation is
     * recorded as a <em>link</em> rather than a parent -- both flavors always start a new trace per the
     * {@link SpanFactory} contract.
     */
    private TestSpan record(TestSpan span, @Nullable ProcessingContext context) {
        SpanScope active = context == null ? null : SpanScope.fromContext(context);
        TestSpan activeSpan = active != null && active.span() instanceof TestSpan testSpan ? testSpan : null;
        if (span.type == TestSpanType.ROOT || span.type == TestSpanType.DISCONNECTED_HANDLER) {
            span.link = activeSpan;
        } else {
            span.contextualParent = activeSpan;
        }
        createdSpans.add(span);
        return span;
    }

    private TestSpan recordMessageSpan(TestSpanType type, String operationName, Message message,
                                       @Nullable ProcessingContext context) {
        TestSpan span = new TestSpan(type, operationName, message);
        spanAttributesProviders.forEach(provider -> provider.provideForMessage(message, context)
                                                            .forEach(span::addAttribute));
        return record(span, context);
    }

    /**
     * Verifies a span with the given name was created and started but not yet ended.
     *
     * @param name the span name
     */
    public void verifySpanActive(String name) {
        assertThat(findSpan(name, span -> span.started && !span.ended.get()))
                .withFailMessage(() -> errorMessage(name))
                .isPresent();
    }

    /**
     * Verifies a span with the given name was created, started, and ended.
     *
     * @param name the span name
     */
    public void verifySpanCompleted(String name) {
        assertThat(findSpan(name, span -> span.started && span.ended.get()))
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
        assertThat(findSpan(name, message, span -> span.started && span.ended.get()))
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
     * Verifies no span whose name starts with the given prefix was created at all. Prefer this over
     * {@link #verifyNoSpan(String)} when the full span name embeds a message type: a regression that wrongly opens
     * the span for a <em>different</em> message would produce a differently-suffixed name that an exact-name check
     * silently misses.
     *
     * @param namePrefix the span-name prefix that must not match any created span
     */
    public void verifyNoSpanWithNamePrefix(String namePrefix) {
        List<TestSpan> matches = createdSpans.stream()
                                             .filter(span -> span.name.startsWith(namePrefix))
                                             .toList();
        assertThat(matches)
                .withFailMessage(() -> String.format(
                        "Expected no span with name prefix '%s' but found:%n%s", namePrefix,
                        matches.stream().map(TestSpan::toString)
                               .collect(Collectors.joining(System.lineSeparator()))))
                .isEmpty();
    }

    /**
     * Verifies exactly {@code expected} spans with the given name were created.
     *
     * @param name     the span name
     * @param expected the exact number of spans expected under that name
     */
    public void verifySpanCount(String name, int expected) {
        long actual = createdSpans.stream().filter(span -> span.name.equals(name)).count();
        assertThat(actual)
                .withFailMessage(() -> String.format(
                        "Expected exactly %d span(s) named '%s' but found %d. Recorded spans:%n%s",
                        expected, name, actual,
                        createdSpans.stream().map(TestSpan::toString)
                                    .collect(Collectors.joining(System.lineSeparator()))))
                .isEqualTo(expected);
    }

    /**
     * Verifies the given {@code context} carries the scope of the span with the given name as its active
     * {@link SpanScope} -- i.e. that specific span, not just any span, is what children created with this context
     * will parent under.
     *
     * @param name    the name of the span whose scope the context must carry
     * @param context the processing context to inspect
     */
    public void verifyContextCarriesScopeOf(String name, ProcessingContext context) {
        SpanScope active = SpanScope.fromContext(context);
        assertThat(active)
                .withFailMessage("Expected the context to carry an active SpanScope, but none was present")
                .isNotNull();
        assertThat(active.span())
                .isInstanceOfSatisfying(TestSpan.class,
                                        span -> assertThat(span.name)
                                                .withFailMessage(() -> String.format(
                                                        "Expected the context to carry the scope of span '%s' but it "
                                                                + "carries '%s'",
                                                        name, span.name))
                                                .isEqualTo(name));
    }

    /**
     * Verifies the scope of the span with the given name was entered (via {@link SpanScope#within}) at least
     * {@code times} times -- for example once for a branch-scoped operation's synchronous window plus once per pull
     * of its result stream.
     *
     * @param name  the span name
     * @param times the minimum number of scope entries expected
     */
    public void verifyScopeEnteredAtLeast(String name, int times) {
        Optional<TestSpan> span = findSpan(name, s -> true);
        assertThat(span).withFailMessage(() -> errorMessage(name)).isPresent();
        assertThat(span.get().scopeEntries)
                .withFailMessage(() -> String.format(
                        "Expected the scope of span '%s' to have been entered at least %d time(s) but it was entered "
                                + "%d time(s)",
                        name, times, span.get().scopeEntries))
                .isGreaterThanOrEqualTo(times);
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

    /**
     * Verifies a span with the given {@code childName} was created with the span named {@code parentName} as its
     * contextual parent (the active {@link SpanScope} on the context at creation time).
     *
     * @param childName  the child span's name
     * @param parentName the expected parent span's name
     */
    public void verifySpanHasParent(String childName, String parentName) {
        Optional<TestSpan> child = findSpan(childName, span -> true);
        assertThat(child).withFailMessage(() -> errorMessage(childName)).isPresent();
        assertThat(child.get().contextualParent)
                .withFailMessage(() -> String.format(
                        "Span '%s' expected parent '%s' but had %s", childName, parentName,
                        child.get().contextualParent == null
                                ? "no parent"
                                : "parent '" + child.get().contextualParent.name + "'"))
                .isNotNull()
                .extracting(parent -> parent.name)
                .isEqualTo(parentName);
    }

    /**
     * Verifies a span with the given {@code name} was created with no contextual parent (no active {@link SpanScope}
     * on the context at creation time).
     *
     * @param name the span name
     */
    public void verifySpanHasNoParent(String name) {
        Optional<TestSpan> span = findSpan(name, s -> true);
        assertThat(span).withFailMessage(() -> errorMessage(name)).isPresent();
        assertThat(span.get().contextualParent)
                .withFailMessage(() -> String.format("Span '%s' expected no parent but had parent '%s'",
                                                     name, span.get().contextualParent == null
                                                             ? null : span.get().contextualParent.name))
                .isNull();
    }

    /**
     * Verifies a span with the given {@code name} was linked to the span named {@code linkedName}. A link is
     * recorded in two flavors, mirroring the {@link SpanFactory} contract: {@link TestSpanType#ROOT} and
     * {@link TestSpanType#DISCONNECTED_HANDLER} spans record the context's active span at creation as the link (both
     * start a new trace but stay navigable back to it). A {@link TestSpanType#CONTEXT_PARENT_HANDLER} span records the
     * span propagated through its handled message, while a {@link TestSpanType#LINKED_HANDLER} span records the span
     * propagated through its explicitly linked message.
     *
     * @param name       the span name
     * @param linkedName the expected linked span's name
     */
    public void verifySpanHasLink(String name, String linkedName) {
        Optional<TestSpan> span = findSpan(name, s -> true);
        assertThat(span).withFailMessage(() -> errorMessage(name)).isPresent();
        assertThat(span.get().link)
                .withFailMessage(() -> String.format(
                        "Span '%s' expected link '%s' but had %s", name, linkedName,
                        span.get().link == null ? "no link" : "link '" + span.get().link.name + "'"))
                .isNotNull()
                .extracting(linked -> linked.name)
                .isEqualTo(linkedName);
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
                             createdSpans.stream()
                                         .map(TestSpan::toString)
                                         .collect(Collectors.joining(System.lineSeparator())));
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
        private final AtomicBoolean ended = new AtomicBoolean(false);
        private int scopeEntries;
        private @Nullable Throwable exception;
        private @Nullable TestSpan contextualParent;
        private @Nullable TestSpan link;

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
            if (ended.compareAndSet(false, true)) {
                synchronized (activeSpans) {
                    activeSpans.remove(this);
                }
                logger.debug("- {}", name);
            }
        }

        @Override
        public String toString() {
            return "TestSpan{type=" + type + ", name='" + name + '\'' + ", started=" + started
                    + ", ended=" + ended.get() + ", exception=" + exception + ", attributes=" + attributes + '}';
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
        public boolean isClosed() {
            return span.ended.get();
        }

        @Override
        public <T> T within(Supplier<T> operation) {
            // Transparent per the SpanScope contract; only counts the entry so tests can assert scope re-entry
            // (e.g. once per pull of a branch-scoped stream).
            span.scopeEntries++;
            return operation.get();
        }

        @Override
        public void close() {
            span.close();
        }
    }
}
