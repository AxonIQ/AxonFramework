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

import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.tracing.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.assertj.core.api.Assertions.assertThat;

class TracingEventHandlingComponentTest {

    private static final String PROCESS_SPAN = "EventProcessor.process MyEvent";
    private static final String BATCH_SPAN = "StreamingEventProcessor.batch";
    private static final String PUBLISHER_SPAN = "publisher";
    private static final String PROCESSOR_NAME_ATTRIBUTE = "axoniq.event_processor.name";

    private TestSpanFactory spanFactory;
    private RecordingEventHandlingComponent delegate;
    private TracingEventHandlingComponent testSubject;
    private TracingEventHandlingComponent streamingSubject;

    private final EventMessage event = new GenericEventMessage(new MessageType("MyEvent"), "the-payload");

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        delegate = new RecordingEventHandlingComponent();
        testSubject = new TracingEventHandlingComponent(delegate, spanFactory);
        streamingSubject = new TracingEventHandlingComponent(
                delegate, spanFactory, /* processorName */ null, /* streaming */ true,
                /* batchTraceEnabled */ true, /* distributedInSameTrace */ false, Duration.ofMinutes(2));
    }

    @Nested
    class PerEventSpan {

        @Test
        void opensAConsumerHandlerSpanPerEvent() {
            // given non-streaming execution semantics -- handlers run inside the publication's unit of work
            ProcessingContext context = new StubProcessingContext();

            // when
            testSubject.handle(event, context);

            // then a per-event CONSUMER span is opened continuing the publisher's trace and, since the delegate handled
            // it synchronously, already completed (closes on its own stream termination, not at batch/context end)
            spanFactory.verifySpanCompleted(PROCESS_SPAN);
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.HANDLER);
            assertThat(delegate.handled).isTrue();
        }

        @Test
        void nonStreamingExecutionContinuesThePublisherTraceEvenForStaleEventsAndRegardlessOfTheToggle() {
            // given non-streaming execution semantics and distributedInSameTrace = false with a stale event -- the
            // streaming-only toggle and its freshness limit must not apply when handlers run inside the publication's
            // unit of work
            TracingEventHandlingComponent subject = new TracingEventHandlingComponent(
                    delegate, spanFactory, /* processorName */ null, /* streaming */ false,
                    /* batchTraceEnabled */ true, /* distributedInSameTrace */ false, Duration.ofMinutes(2));
            ProcessingContext context = new StubProcessingContext();
            EventMessage staleEvent = new GenericEventMessage(
                    "event-id", new MessageType("MyEvent"), "the-payload", Map.of(),
                    Instant.now().minus(Duration.ofMinutes(30)));

            // when
            subject.handle(staleEvent, context);

            // then the per-event span still continues the publisher's trace
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.HANDLER);
        }
    }

    @Nested
    class BatchSpan {

        @Test
        void opensABatchRootSpanForStreamingExecution() {
            // given streaming execution semantics -- configured explicitly, with nothing streaming-specific (such as
            // a Segment) on the context, covering asynchronous consumers like persistent-stream-fed processors
            ProcessingContext context = new StubProcessingContext();
            Span publisher = spanFactory.createDispatchSpan(PUBLISHER_SPAN, event, null);
            publisher.propagateContext(event);

            // when
            streamingSubject.handle(event, context);

            // then a batch root span is opened, enclosing the per-event spans
            spanFactory.verifySpanActive(BATCH_SPAN);
            spanFactory.verifySpanHasType(BATCH_SPAN, TestSpanType.ROOT);
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.CONTEXT_PARENT_HANDLER);
            spanFactory.verifySpanHasParent(PROCESS_SPAN, BATCH_SPAN);
            spanFactory.verifySpanHasLink(PROCESS_SPAN, PUBLISHER_SPAN);
        }

        @Test
        void doesNotOpenABatchSpanForNonStreamingExecution() {
            // given non-streaming execution semantics
            ProcessingContext context = new StubProcessingContext();

            // when
            testSubject.handle(event, context);

            // then no batch span is produced (AF parity: the handler runs inside the publisher's unit of work)
            spanFactory.verifyNoSpan(BATCH_SPAN);
        }

        @Test
        void suppressesTheBatchSpanWhenBatchTraceDisabled() {
            // given streaming execution semantics but with batchTraceEnabled = false
            TracingEventHandlingComponent disabledBatchTrace = new TracingEventHandlingComponent(
                    delegate, spanFactory, /* processorName */ null, /* streaming */ true,
                    /* batchTraceEnabled */ false, /* distributedInSameTrace */ true, Duration.ofMinutes(2));
            ProcessingContext context = new StubProcessingContext();

            // when
            disabledBatchTrace.handle(event, context);

            // then no batch root span is produced; the per-event handler span still is (and, handled synchronously,
            // already completed)
            spanFactory.verifyNoSpan(BATCH_SPAN);
            spanFactory.verifySpanCompleted(PROCESS_SPAN);
        }

        @Test
        void suppressesTheBatchSpanInDistributedInSameTraceMode() {
            // given streaming execution semantics with distributedInSameTrace = true -- per-event spans continue
            // their publishers' traces, so a batch root would dangle without meaningful children
            TracingEventHandlingComponent sameTrace = new TracingEventHandlingComponent(
                    delegate, spanFactory, /* processorName */ null, /* streaming */ true,
                    /* batchTraceEnabled */ true, /* distributedInSameTrace */ true, Duration.ofMinutes(2));
            ProcessingContext context = new StubProcessingContext();

            // when
            sameTrace.handle(event, context);

            // then no batch root span is produced; the per-event span continues the publisher's trace
            spanFactory.verifyNoSpan(BATCH_SPAN);
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.HANDLER);
        }
    }

    @Nested
    class ProcessorNameAttribute {

        private TracingEventHandlingComponent namedProcessorSubject() {
            return new TracingEventHandlingComponent(
                    delegate, spanFactory, /* processorName */ "my-processor", /* streaming */ true,
                    /* batchTraceEnabled */ true, /* distributedInSameTrace */ false, Duration.ofMinutes(2));
        }

        @Test
        void attachesTheProcessorNameToThePerEventHandlerSpan() {
            // given a component owned by a named processor, handling on a subscribing-style context
            ProcessingContext context = new StubProcessingContext();

            // when
            namedProcessorSubject().handle(event, context);

            // then the handler span identifies its owning processor
            spanFactory.verifySpanHasAttributeValue(
                    PROCESS_SPAN, PROCESSOR_NAME_ATTRIBUTE, "my-processor");
        }

        @Test
        void attachesTheProcessorNameToTheBatchSpan() {
            // given a component owned by a named processor, handling in a streaming batch
            ProcessingContext context = new StubProcessingContext();

            // when
            namedProcessorSubject().handle(event, context);

            // then the batch root span identifies its owning processor too
            spanFactory.verifySpanHasAttributeValue(
                    BATCH_SPAN, PROCESSOR_NAME_ATTRIBUTE, "my-processor");
        }

        @Test
        void attachesNoProcessorNameWhenItIsUnknown() {
            // given the convenience construction without a processor name
            ProcessingContext context = new StubProcessingContext();

            // when
            testSubject.handle(event, context);

            // then the handler span carries no processor-name attribute
            spanFactory.verifySpanHasNoAttribute(
                    PROCESS_SPAN, PROCESSOR_NAME_ATTRIBUTE);
        }
    }

    @Nested
    class DistributedInSameTrace {

        @Test
        void usesContextParentHandlerSpanForStreamingExecutionWhenBatchTracingIsEnabled() {
            // given streaming execution semantics (batch tracing enabled, distributedInSameTrace=false)
            ProcessingContext context = new StubProcessingContext();

            // when
            streamingSubject.handle(event, context);

            // then the per-event span is parented to the batch context and linked to the publisher
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.CONTEXT_PARENT_HANDLER);
        }

        @Test
        void usesDisconnectedHandlerSpanWhenBatchTracingIsDisabled() {
            // given streaming execution semantics with no batch trace and distributedInSameTrace=false
            TracingEventHandlingComponent noBatch = new TracingEventHandlingComponent(
                    delegate, spanFactory, /* processorName */ null, /* streaming */ true,
                    /* batchTraceEnabled */ false, /* distributedInSameTrace */ false, Duration.ofMinutes(2));
            ProcessingContext context = new StubProcessingContext();

            // when
            noBatch.handle(event, context);

            // then the per-event span starts a new trace linked to the publisher
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.DISCONNECTED_HANDLER);
        }

        @Test
        void staysInTheSameTraceForAnEventWithinTheTimeLimit() {
            // given streaming execution semantics and distributedInSameTrace = true with a two-minute limit and an
            // event published just now
            TracingEventHandlingComponent sameTrace = new TracingEventHandlingComponent(
                    delegate, spanFactory, /* processorName */ null, /* streaming */ true,
                    /* batchTraceEnabled */ true, /* distributedInSameTrace */ true, Duration.ofMinutes(2));
            ProcessingContext context = new StubProcessingContext();

            // when
            sameTrace.handle(eventWithTimestamp(Instant.now()), context);

            // then the per-event span continues the publisher's trace
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.HANDLER);
        }

        @Test
        void fallsBackToADisconnectedSpanForAnEventOlderThanTheTimeLimit() {
            // given streaming execution semantics and distributedInSameTrace = true with a two-minute limit and an
            // event published three minutes ago; stale events such as replays must not stretch the publisher's
            // long-finished trace
            TracingEventHandlingComponent sameTrace = new TracingEventHandlingComponent(
                    delegate, spanFactory, /* processorName */ null, /* streaming */ true,
                    /* batchTraceEnabled */ true, /* distributedInSameTrace */ true, Duration.ofMinutes(2));
            ProcessingContext context = new StubProcessingContext();

            // when
            sameTrace.handle(eventWithTimestamp(Instant.now().minus(Duration.ofMinutes(3))), context);

            // then the per-event span starts its own trace, linked back to the publisher
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.DISCONNECTED_HANDLER);
        }

        private EventMessage eventWithTimestamp(Instant timestamp) {
            return new GenericEventMessage(
                    "event-id", new MessageType("MyEvent"), "the-payload", Map.of(), timestamp);
        }
    }

    /**
     * Regression tests for the branch-carried-span model: a per-event handler span must parent its children through
     * the context branch it opened, independent of which event started most recently on the shared batch context.
     */
    @Nested
    class BranchCarriedSpans {

        private static final String CHILD_SPAN = "child-span";
        private static final String EVENT_1_TYPE = "Event1";
        private static final String EVENT_2_TYPE = "Event2";
        private static final String PROCESS_SPAN_E1 = "EventProcessor.process " + EVENT_1_TYPE;
        private static final String PROCESS_SPAN_E2 = "EventProcessor.process " + EVENT_2_TYPE;

        private final EventMessage event1 = new GenericEventMessage(new MessageType(EVENT_1_TYPE), "e1");
        private final EventMessage event2 = new GenericEventMessage(new MessageType(EVENT_2_TYPE), "e2");

        private TracingEventHandlingComponent streamingComponent(EventHandlingComponent delegate) {
            return new TracingEventHandlingComponent(
                    delegate, spanFactory, /* processorName */ null, /* streaming */ true,
                    /* batchTraceEnabled */ true, /* distributedInSameTrace */ false, Duration.ofMinutes(2));
        }

        @Test
        void asyncContinuationChildSpanParentsUnderItsOwnEventEvenAfterALaterEventStarted() {
            // given a streaming batch and a delegate that defers event 1's completion until triggered explicitly
            // -- a real (branching) ProcessingContext is required here: unlike production contexts, the
            // StubProcessingContext
            // mutates its resources map in place instead of branching on withResource, which would mask exactly the
            // bug this test guards against
            CompletableFuture<Void> event1Trigger = new CompletableFuture<>();
            DeferringEventHandlingComponent deferringDelegate =
                    new DeferringEventHandlingComponent(spanFactory, EVENT_1_TYPE, event1Trigger);
            TracingEventHandlingComponent subject = streamingComponent(deferringDelegate);

            // when event 1's handling starts but does not complete yet, then event 2 starts and completes
            inRealProcessingContext(context -> {
                subject.handle(event1, context);
                subject.handle(event2, context);
                // and only now does event 1's continuation run, creating a child span
                event1Trigger.complete(null);
            });

            // then the child span parents under event 1's own handler span, not event 2's (started later)
            spanFactory.verifySpanHasParent(CHILD_SPAN, PROCESS_SPAN_E1);
        }

        @Test
        void spanCreatedAfterTheBatchLoopParentsUnderTheBatchSpanNotTheLastHandlerSpan() {
            // given a streaming batch of two synchronously-handled events -- a real (branching) ProcessingContext is
            // required, see the note in the test above
            TracingEventHandlingComponent subject = streamingComponent(delegate);
            String postBatchSpan = "post-batch-span";

            // when both events are handled (simulating the batch loop), and something outside the loop creates a
            // span from the shared batch context (e.g. a lifecycle-action child running once per batch, such as the
            // commit-phase flush)
            inRealProcessingContext(context -> {
                subject.handle(event1, context);
                subject.handle(event2, context);
                spanFactory.createInternalSpan(postBatchSpan, context).coverLifecycle(context);
            });

            // then it parents under the batch span, not event 2's (last-started) handler span
            spanFactory.verifySpanHasParent(postBatchSpan, BATCH_SPAN);
        }

        /**
         * Runs {@code action} with a real, branching {@link ProcessingContext} (a production {@link UnitOfWork}'s),
         * as opposed to {@link StubProcessingContext}, which mutates its resources map in place instead of branching
         * on {@code withResource} -- unsuitable for asserting branch-isolation behavior.
         */
        private void inRealProcessingContext(Consumer<ProcessingContext> action) {
            UnitOfWork unitOfWork = UnitOfWorkTestUtils.aUnitOfWork();
            unitOfWork.onInvocation(context -> {
                action.accept(context);
                return CompletableFuture.completedFuture(null);
            });
            joinAndUnwrap(unitOfWork.execute());
        }

        @Test
        void perEventScopeClosesOnItsOwnStreamTerminationNotAtBatchEnd() {
            // given a streaming batch and a delegate that completes synchronously
            ProcessingContext context = new StubProcessingContext();
            TracingEventHandlingComponent subject = streamingComponent(delegate);

            // when only the first event is handled -- its own stream already terminated -- and the batch itself
            // (the shared context) has not completed
            subject.handle(event1, context);

            // then its handler span is already completed; it did not wait for the (never-completing) batch context
            spanFactory.verifySpanCompleted(PROCESS_SPAN_E1);
        }

        @Test
        void syncTwoEventBatchEachCreatingAChildDuringTheBodyParentsUnderItsOwnEvent() {
            // given a streaming batch context and a delegate that synchronously opens a child span while handling
            String childOfEvent1 = "child-of-event-1";
            String childOfEvent2 = "child-of-event-2";
            ProcessingContext context = new StubProcessingContext();
            SyncChildCreatingEventHandlingComponent syncDelegate = new SyncChildCreatingEventHandlingComponent(
                    spanFactory, Map.of(EVENT_1_TYPE, childOfEvent1, EVENT_2_TYPE, childOfEvent2));
            TracingEventHandlingComponent subject = streamingComponent(syncDelegate);

            // when both events are handled synchronously, each creating a child span during its own body
            subject.handle(event1, context);
            subject.handle(event2, context);

            // then each child span parents under its own event's handler span
            spanFactory.verifySpanHasParent(childOfEvent1, PROCESS_SPAN_E1);
            spanFactory.verifySpanHasParent(childOfEvent2, PROCESS_SPAN_E2);
        }

        /**
         * Defers completion of the configured event type's handling stream until externally triggered; every other
         * event type completes immediately. Simulates an async handler continuation running after a later event in
         * the same batch has already started.
         */
        private static final class DeferringEventHandlingComponent implements EventHandlingComponent {

            private final SpanFactory spanFactory;
            private final String deferredEventType;
            private final CompletableFuture<Void> trigger;

            private DeferringEventHandlingComponent(SpanFactory spanFactory, String deferredEventType,
                                                     CompletableFuture<Void> trigger) {
                this.spanFactory = spanFactory;
                this.deferredEventType = deferredEventType;
                this.trigger = trigger;
            }

            @Override
            public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
                if (!event.type().qualifiedName().name().equals(deferredEventType)) {
                    return MessageStream.empty();
                }
                CompletableFuture<Message> deferred = trigger.thenApply(ignored -> {
                    spanFactory.createInternalSpan(CHILD_SPAN, context).coverLifecycle(context);
                    return null;
                });
                return MessageStream.<Message>fromFuture(deferred).ignoreEntries();
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
            public void describeTo(ComponentDescriptor descriptor) {
                // not relevant to these tests
            }
        }

        /**
         * Synchronously opens a per-event child span (name resolved from the handled event's type) during handling.
         */
        private static final class SyncChildCreatingEventHandlingComponent implements EventHandlingComponent {

            private final SpanFactory spanFactory;
            private final Map<String, String> childSpanNamesByEventType;

            private SyncChildCreatingEventHandlingComponent(SpanFactory spanFactory,
                                                             Map<String, String> childSpanNamesByEventType) {
                this.spanFactory = spanFactory;
                this.childSpanNamesByEventType = childSpanNamesByEventType;
            }

            @Override
            public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
                String childSpanName = childSpanNamesByEventType.get(event.type().qualifiedName().name());
                spanFactory.createInternalSpan(childSpanName, context).coverLifecycle(context);
                return MessageStream.empty();
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
            public void describeTo(ComponentDescriptor descriptor) {
                // not relevant to these tests
            }
        }
    }

    @Nested
    class Delegation {

        @Test
        void forwardsSupportedEventsToTheDelegate() {
            // when / then
            assertThat(testSubject.supportedEvents()).isEqualTo(delegate.supportedEvents());
        }

        @Test
        void describesItselfAsAWrapperOfTheDelegate() {
            // given
            RecordingComponentDescriptor descriptor = new RecordingComponentDescriptor();

            // when
            testSubject.describeTo(descriptor);

            // then
            assertThat(descriptor.wrapped).isSameAs(delegate);
        }
    }

    /**
     * Minimal {@link EventHandlingComponent} stub recording that it handled an event.
     */
    private static final class RecordingEventHandlingComponent implements EventHandlingComponent {

        private boolean handled;

        @Override
        public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
            handled = true;
            return MessageStream.empty();
        }

        @Override
        public Set<QualifiedName> supportedEvents() {
            return Set.of(new QualifiedName("MyEvent"));
        }

        @Override
        public Object sequenceIdentifierFor(EventMessage event, ProcessingContext context) {
            return event.identifier();
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            // not relevant to these tests
        }
    }

    /**
     * Captures the single {@code describeWrapperOf} target for introspection assertions.
     */
    private static final class RecordingComponentDescriptor implements ComponentDescriptor {

        private Object wrapped;

        @Override
        public void describeWrapperOf(Object delegate) {
            this.wrapped = delegate;
        }

        @Override
        public void describeProperty(String name, Object object) {
        }

        @Override
        public void describeProperty(String name, java.util.Collection<?> collection) {
        }

        @Override
        public void describeProperty(String name, java.util.Map<?, ?> map) {
        }

        @Override
        public void describeProperty(String name, String value) {
        }

        @Override
        public void describeProperty(String name, Long value) {
        }

        @Override
        public void describeProperty(String name, Boolean value) {
        }
    }
}
