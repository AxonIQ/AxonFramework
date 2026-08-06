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

package org.axonframework.hunt.probe.tracing;

import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.tracing.TracingEventHandlingComponent;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.SpanScope;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic pin of the interleaving behind the intermittent failure of the framework's own
 * {@code TracingEventHandlingComponentTest$BatchSpan#concurrentFirstEventsCreateAndBindExactlyOneBatchSpan}
 * (observed: "Span 'EventProcessor.process SecondEvent' expected parent 'StreamingEventProcessor.batch' but had
 * parent 'EventProcessor.process FirstEvent'").
 * <p>
 * Claims C44 and gap M22 in {@code docs/testing-plans/axon-hunt.md}.
 * <p>
 * The mechanism has nothing to do with the batch-span create-and-bind path (that is atomic:
 * {@code computeResourceIfAbsent} plus a synchronized initializer). It is a scope leak through the
 * {@code ProcessingContext} test double: {@code StubProcessingContext.withResource} mutates the shared context and
 * returns {@code this}, violating the {@link ProcessingContext#withResource} contract ("Constructs a new
 * ProcessingContext, branching off"). {@link Span#branchStream} therefore writes the per-event handler scope onto
 * the SHARED batch context instead of onto a private branch, and a second event whose span is created while the
 * first event's handler scope is still open resolves that leaked scope as its parent. Once the first scope closes,
 * {@code BranchSpanScope.resolve} falls back to the batch scope, which is why the framework test almost always
 * passes: the window is the synchronous handling time of one event.
 * <p>
 * Both nested cases force the exact interleaving single-threadedly (no barriers, no sleeps): the first event's
 * delegate stream stays open while the second event's span is created.
 * <ul>
 *   <li>Against a context honoring the branching contract (the interface default,
 *       {@code ResourceOverridingProcessingContext} -- what the production {@code UnitOfWork} context uses), the
 *       second span parents under the batch span. Production is not affected.</li>
 *   <li>Against a context replicating {@code StubProcessingContext}'s mutating {@code withResource}, the second
 *       span parents under the first event's handler span -- reproducing the flake deterministically.</li>
 * </ul>
 */
class BatchSpanParentScopeLeakTest {

    private static final String BATCH_SPAN = "StreamingEventProcessor.batch";
    private static final String FIRST_PROCESS_SPAN = "EventProcessor.process FirstEvent";
    private static final String SECOND_PROCESS_SPAN = "EventProcessor.process SecondEvent";
    private static final String THIRD_PROCESS_SPAN = "EventProcessor.process ThirdEvent";

    private final RecordingSpanFactory spanFactory = new RecordingSpanFactory();
    private final PendingStreamDelegate delegate = new PendingStreamDelegate();
    private final TracingEventHandlingComponent streamingSubject = new TracingEventHandlingComponent(
            delegate, spanFactory, /* processorName */ null, /* streaming */ true,
            /* batchTraceEnabled */ true, /* distributedInSameTrace */ false, Duration.ofMinutes(2));

    private static EventMessage event(String name) {
        return new GenericEventMessage(new MessageType(name), name.toLowerCase());
    }

    @Nested
    class UnderTheBranchingContractProductionUses {

        @Test
        void secondEventParentsUnderTheBatchSpanEvenWhileTheFirstHandlerScopeIsStillOpen() {
            // given a shared batch context whose withResource branches immutably (the ProcessingContext interface
            // default, which is what the production UnitOfWork context uses)
            ProbeProcessingContext context = new ProbeProcessingContext();

            // when the first event's handling is still in flight (its delegate stream has not terminated, so its
            // branch-scoped handler span is still open) and the second event's span is created
            streamingSubject.handle(event("FirstEvent"), context);
            assertThat(spanFactory.scopeOf(FIRST_PROCESS_SPAN).isClosed()).isFalse();
            streamingSubject.handle(event("SecondEvent"), context);

            // then exactly one batch span exists and both per-event spans parent under it
            assertThat(spanFactory.countByName(BATCH_SPAN)).isEqualTo(1);
            assertThat(spanFactory.parentOf(FIRST_PROCESS_SPAN)).isEqualTo(BATCH_SPAN);
            assertThat(spanFactory.parentOf(SECOND_PROCESS_SPAN)).isEqualTo(BATCH_SPAN);
        }
    }

    @Nested
    class UnderTheMutatingStubSemanticsTheFrameworkTestUses {

        @Test
        void secondEventParentsUnderTheFirstEventsOpenHandlerScopeReproducingTheFlakeDeterministically() {
            // given a shared batch context replicating StubProcessingContext#withResource, which mutates the shared
            // resource map and returns this instead of branching
            MutatingResourceProcessingContext context = new MutatingResourceProcessingContext();

            // when the second event's span is created while the first event's handler scope is still open
            streamingSubject.handle(event("FirstEvent"), context);
            assertThat(spanFactory.scopeOf(FIRST_PROCESS_SPAN).isClosed()).isFalse();
            streamingSubject.handle(event("SecondEvent"), context);

            // then the leaked branch scope wins parent resolution: the observed framework-test failure, every time
            assertThat(spanFactory.countByName(BATCH_SPAN)).isEqualTo(1);
            assertThat(spanFactory.parentOf(SECOND_PROCESS_SPAN)).isEqualTo(FIRST_PROCESS_SPAN);
        }

        @Test
        void onceTheFirstHandlerScopeClosesResolutionFallsBackToTheBatchSpanWhichIsWhyTheFlakeIsRare() {
            // given the same mutating context, with the first event's handler scope already closed
            MutatingResourceProcessingContext context = new MutatingResourceProcessingContext();
            streamingSubject.handle(event("FirstEvent"), context);
            spanFactory.scopeOf(FIRST_PROCESS_SPAN).close();

            // when a later event's span is created after the leaked scope closed
            streamingSubject.handle(event("ThirdEvent"), context);

            // then BranchSpanScope.resolve skips the closed leaked scope and falls back to the batch scope: the
            // framework test passes whenever the first handler finished before the second span was created
            assertThat(spanFactory.parentOf(THIRD_PROCESS_SPAN)).isEqualTo(BATCH_SPAN);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Probe doubles. TestSpanFactory and StubProcessingContext live in messaging's test sources, which this
    // module does not depend on; these are the minimal equivalents the probe needs.
    // ------------------------------------------------------------------------------------------------

    /**
     * Delegate whose result stream never terminates on its own, keeping the branch-scoped handler span open --
     * the exact in-flight state the race window needs.
     */
    private static final class PendingStreamDelegate implements EventHandlingComponent {

        @Override
        public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
            return MessageStream.<Message>fromFuture(new CompletableFuture<>()).ignoreEntries();
        }

        @Override
        public Set<QualifiedName> supportedEvents() {
            return Set.of();
        }

        @Override
        public Object sequenceIdentifierFor(EventMessage event, ProcessingContext context) {
            return event.identifier();
        }

        @Override
        public void describeTo(org.axonframework.common.infra.ComponentDescriptor descriptor) {
            descriptor.describeProperty("type", "PendingStreamDelegate");
        }
    }

    /**
     * Recording {@link SpanFactory} capturing, per created span, the active scope resolved from the supplied
     * context at creation time -- the same parent edge a real factory resolves.
     */
    private static final class RecordingSpanFactory implements SpanFactory {

        private record Recorded(String name, @Nullable String parentName, RecordedSpan span) {

        }

        private final List<Recorded> created = new CopyOnWriteArrayList<>();

        private Span create(String operationName, @Nullable ProcessingContext context, boolean newTrace) {
            SpanScope active = context == null ? null : SpanScope.fromContext(context);
            String parent = active != null && active.span() instanceof RecordedSpan recorded && !newTrace
                    ? recorded.name
                    : null;
            RecordedSpan span = new RecordedSpan(operationName);
            created.add(new Recorded(operationName, parent, span));
            return span;
        }

        @Override
        public Span createDispatchSpan(String operationName, Message message, @Nullable ProcessingContext context) {
            return create(operationName, context, false);
        }

        @Override
        public Span createHandlerSpan(String operationName, Message message, @Nullable ProcessingContext context) {
            return create(operationName, context, false);
        }

        @Override
        public Span createContextParentHandlerSpan(String operationName, Message message,
                                                   @Nullable ProcessingContext context) {
            return create(operationName, context, false);
        }

        @Override
        public Span createLinkedHandlerSpan(String operationName, Message message, Message linkedMessage,
                                            @Nullable ProcessingContext context) {
            return create(operationName, context, false);
        }

        @Override
        public Span createInternalSpan(String operationName, @Nullable ProcessingContext context) {
            return create(operationName, context, false);
        }

        @Override
        public Span createRootSpan(String operationName, @Nullable ProcessingContext context) {
            return create(operationName, context, true);
        }

        @Override
        public Span createDisconnectedHandlerSpan(String operationName, Message message,
                                                  @Nullable ProcessingContext context) {
            return create(operationName, context, true);
        }

        @Override
        public void describeTo(org.axonframework.common.infra.ComponentDescriptor descriptor) {
            descriptor.describeProperty("type", "RecordingSpanFactory");
        }

        long countByName(String name) {
            return created.stream().filter(recorded -> recorded.name().equals(name)).count();
        }

        @Nullable String parentOf(String name) {
            return created.stream()
                          .filter(recorded -> recorded.name().equals(name))
                          .findFirst()
                          .orElseThrow(() -> new AssertionError("No span named " + name))
                          .parentName();
        }

        SpanScope scopeOf(String name) {
            return created.stream()
                          .filter(recorded -> recorded.name().equals(name))
                          .findFirst()
                          .orElseThrow(() -> new AssertionError("No span named " + name))
                          .span().scope;
        }

        private static final class RecordedSpan implements Span {

            private final String name;
            private final RecordedScope scope = new RecordedScope(this);

            private RecordedSpan(String name) {
                this.name = name;
            }

            @Override
            public SpanScope start() {
                return scope;
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

        private static final class RecordedScope implements SpanScope {

            private final RecordedSpan span;
            private final AtomicBoolean closed = new AtomicBoolean(false);

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
                closed.set(true);
            }

            @Override
            public <T> T within(Supplier<T> operation) {
                return operation.get();
            }
        }
    }

    /**
     * Minimal {@link ProcessingContext} with a concurrent resource map and no-op lifecycle registration. It does
     * NOT override {@link ProcessingContext#withResource}, so branching follows the interface contract: a new
     * {@code ResourceOverridingProcessingContext} per branch, exactly like the production {@code UnitOfWork}
     * context.
     */
    private static class ProbeProcessingContext implements ProcessingContext {

        private final Map<Context.ResourceKey<?>, Object> resources = new ConcurrentHashMap<>();

        @Override
        public boolean isStarted() {
            return true;
        }

        @Override
        public boolean isError() {
            return false;
        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public boolean isCompleted() {
            return false;
        }

        @Override
        public ProcessingLifecycle on(Phase phase, Function<ProcessingContext, CompletableFuture<?>> action) {
            return this;
        }

        @Override
        public ProcessingLifecycle onError(ErrorHandler action) {
            return this;
        }

        @Override
        public ProcessingLifecycle whenComplete(Consumer<ProcessingContext> action) {
            return this;
        }

        @Override
        public boolean containsResource(Context.ResourceKey<?> key) {
            return resources.containsKey(key);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getResource(Context.ResourceKey<T> key) {
            return (T) resources.get(key);
        }

        @Override
        public Map<Context.ResourceKey<?>, Object> resources() {
            return Map.copyOf(resources);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T putResource(Context.ResourceKey<T> key, T resource) {
            return (T) resources.put(key, resource);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T updateResource(Context.ResourceKey<T> key, UnaryOperator<T> resourceUpdater) {
            return (T) resources.compute(key, (k, current) -> resourceUpdater.apply((T) current));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T putResourceIfAbsent(Context.ResourceKey<T> key, T resource) {
            return (T) resources.putIfAbsent(key, resource);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T computeResourceIfAbsent(Context.ResourceKey<T> key, Supplier<T> resourceSupplier) {
            return (T) resources.computeIfAbsent(key, k -> resourceSupplier.get());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T removeResource(Context.ResourceKey<T> key) {
            return (T) resources.remove(key);
        }

        @Override
        public <T> boolean removeResource(Context.ResourceKey<T> key, T expectedResource) {
            return resources.remove(key, expectedResource);
        }

        @Override
        public <C> C component(Class<C> type, @Nullable String name) {
            return EmptyApplicationContext.INSTANCE.component(type, name);
        }
    }

    /**
     * Replicates {@code StubProcessingContext}'s contract violation: {@code withResource} writes to the shared
     * resource map and returns {@code this} instead of branching. This is the semantics under which the
     * framework's own concurrency test runs, and the sole ingredient the mis-parenting needs.
     */
    private static final class MutatingResourceProcessingContext extends ProbeProcessingContext {

        @Override
        public <T> ProcessingContext withResource(Context.ResourceKey<T> key, T resource) {
            Objects.requireNonNull(resource, "resource may not be null");
            putResource(key, resource);
            return this;
        }
    }
}
