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

import org.axonframework.messaging.eventhandling.tracing.TracingEventHandlingComponent;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TracingEventHandlingComponentTest {

    private static final String PROCESS_SPAN = "EventProcessor.process MyEvent";
    private static final String BATCH_SPAN = "StreamingEventProcessor.batch";

    private TestSpanFactory spanFactory;
    private RecordingEventHandlingComponent delegate;
    private TracingEventHandlingComponent testSubject;

    private final EventMessage event = new GenericEventMessage(new MessageType("MyEvent"), "the-payload");

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        delegate = new RecordingEventHandlingComponent();
        testSubject = new TracingEventHandlingComponent(delegate, spanFactory);
    }

    @Nested
    class PerEventSpan {

        @Test
        void opensAConsumerHandlerSpanPerEvent() {
            // given a subscribing-style context (no segment)
            ProcessingContext context = new StubProcessingContext();

            // when
            testSubject.handle(event, context);

            // then a per-event CONSUMER span is opened (disconnected by default -- AF4 parity, see DistributedInSameTrace)
            spanFactory.verifySpanActive(PROCESS_SPAN);
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.DISCONNECTED_HANDLER);
            assertThat(delegate.handled).isTrue();
        }
    }

    @Nested
    class BatchSpan {

        @Test
        void opensABatchRootSpanForStreamingProcessors() {
            // given a streaming-batch context -- a Segment is present (written by the pooled-streaming work package)
            ProcessingContext context = new StubProcessingContext();
            context.putResource(Segment.RESOURCE_KEY, Segment.ROOT_SEGMENT);

            // when
            testSubject.handle(event, context);

            // then a batch root span is opened, enclosing the per-event spans
            spanFactory.verifySpanActive(BATCH_SPAN);
            spanFactory.verifySpanHasType(BATCH_SPAN, TestSpanType.ROOT);
        }

        @Test
        void doesNotOpenABatchSpanForSubscribingProcessors() {
            // given a subscribing-style context (no segment)
            ProcessingContext context = new StubProcessingContext();

            // when
            testSubject.handle(event, context);

            // then no batch span is produced (AF parity: subscribing runs inside the publisher's unit of work)
            spanFactory.verifyNoSpan(BATCH_SPAN);
        }

        @Test
        void suppressesTheBatchSpanWhenDisableBatchTraceIsTrue() {
            // given a streaming-batch context but with disableBatchTrace = true (P4.4a toggle)
            TracingEventHandlingComponent disabledBatchTrace = new TracingEventHandlingComponent(
                    delegate, spanFactory, /* disableBatchTrace */ true, /* distributedInSameTrace */ true,
                    Duration.ofMinutes(2));
            ProcessingContext context = new StubProcessingContext();
            context.putResource(Segment.RESOURCE_KEY, Segment.ROOT_SEGMENT);

            // when
            disabledBatchTrace.handle(event, context);

            // then no batch root span is produced; the per-event handler span still is
            spanFactory.verifyNoSpan(BATCH_SPAN);
            spanFactory.verifySpanActive(PROCESS_SPAN);
        }
    }

    @Nested
    class DistributedInSameTrace {

        @Test
        void usesCreateDisconnectedHandlerSpanByDefault() {
            // given the default constructor (distributedInSameTrace=false, AF4 parity)
            ProcessingContext context = new StubProcessingContext();

            // when
            testSubject.handle(event, context);

            // then the per-event span is a DISCONNECTED_HANDLER (new trace + link to publisher)
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.DISCONNECTED_HANDLER);
        }

        @Test
        void staysInTheSameTraceForAnEventWithinTheTimeLimit() {
            // given distributedInSameTrace = true with a two-minute limit and an event published just now
            TracingEventHandlingComponent sameTrace = new TracingEventHandlingComponent(
                    delegate, spanFactory, /* disableBatchTrace */ false, /* distributedInSameTrace */ true,
                    Duration.ofMinutes(2));
            ProcessingContext context = new StubProcessingContext();

            // when
            sameTrace.handle(eventWithTimestamp(Instant.now()), context);

            // then the per-event span continues the publisher's trace
            spanFactory.verifySpanHasType(PROCESS_SPAN, TestSpanType.HANDLER);
        }

        @Test
        void fallsBackToADisconnectedSpanForAnEventOlderThanTheTimeLimit() {
            // given distributedInSameTrace = true with a two-minute limit and an event published three minutes ago
            // (AF4 parity: stale events -- e.g. replays -- must not stretch the publisher's long-finished trace)
            TracingEventHandlingComponent sameTrace = new TracingEventHandlingComponent(
                    delegate, spanFactory, /* disableBatchTrace */ false, /* distributedInSameTrace */ true,
                    Duration.ofMinutes(2));
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
