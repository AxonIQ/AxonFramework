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

import org.axonframework.messaging.tracing.attributes.MetadataSpanAttributesProvider;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory.TestSpanType;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.interception.CorrelationDataInterceptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.assertj.core.api.Assertions.assertThat;

class TracingEventSinkTest {

    private static final String PUBLISH_SPAN = "EventSink.publish MyEvent";

    private TestSpanFactory spanFactory;
    private RecordingEventSink delegate;
    private TracingEventSink testSubject;

    private final EventMessage event = new GenericEventMessage(new MessageType("MyEvent"), "the-payload");

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        spanFactory.registerAttributesProvider(new MetadataSpanAttributesProvider());
        delegate = new RecordingEventSink();
        testSubject = new TracingEventSink(delegate, spanFactory);
    }

    @Nested
    class Publish {

        @Test
        void opensADispatchSpanPerEventAndPropagatesContextOntoIt() {
            // when
            joinAndUnwrap(testSubject.publish(null, List.of(event)));

            // then
            spanFactory.verifySpanCompleted(PUBLISH_SPAN);
            spanFactory.verifySpanHasType(PUBLISH_SPAN, TestSpanType.DISPATCH);
            spanFactory.verifySpanPropagated(PUBLISH_SPAN, event);
        }

        @Test
        void addsCorrelationMetadataFromProcessingContextToThePublishSpan() {
            // given
            ProcessingContext context = new StubProcessingContext()
                    .withResource(CorrelationDataInterceptor.CORRELATION_DATA, Map.of(
                            "gameId", "game-1",
                            "playerId", "player-1"
                    ));

            // when
            joinAndUnwrap(testSubject.publish(context, List.of(event)));

            // then
            spanFactory.verifySpanHasAttributeValue(
                    PUBLISH_SPAN, MetadataSpanAttributesProvider.METADATA_PREFIX + "gameId", "game-1"
            );
            spanFactory.verifySpanHasAttributeValue(
                    PUBLISH_SPAN, MetadataSpanAttributesProvider.METADATA_PREFIX + "playerId", "player-1"
            );
        }

        @Test
        void publishesWithoutOpeningACommitSpanWhenNoProcessingContext() {
            // when
            joinAndUnwrap(testSubject.publish(null, List.of(event)));

            // then
            spanFactory.verifyNoSpanWithNamePrefix("EventBus.commitEvents");
            assertThat(delegate.publishedEvents.get()).hasSize(1);
        }

        @Test
        void publishesWithoutOpeningACommitSpanWhenProcessingContextIsPresent() {
            // given
            ProcessingContext context = new StubProcessingContext();

            // when
            joinAndUnwrap(testSubject.publish(context, List.of(event)));

            // then
            spanFactory.verifyNoSpanWithNamePrefix("EventBus.commitEvents");
            assertThat(delegate.publishedContext.get()).isSameAs(context);
        }
    }

    @Nested
    class Introspection {

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
     * Minimal {@link EventSink} stub recording the published events and context.
     */
    private static final class RecordingEventSink implements EventSink {

        private final AtomicReference<List<? extends EventMessage>> publishedEvents = new AtomicReference<>();
        private final AtomicReference<ProcessingContext> publishedContext = new AtomicReference<>();

        @Override
        public CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                               List<? extends EventMessage> events) {
            publishedContext.set(context);
            publishedEvents.set(events);
            return CompletableFuture.completedFuture(null);
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

        private @Nullable Object wrapped;

        @Override
        public void describeWrapperOf(Object delegate) {
            this.wrapped = delegate;
        }

        @Override
        public void describeProperty(String name, @Nullable Object object) {
        }

        @Override
        public void describeProperty(String name, @Nullable Collection<?> collection) {
        }

        @Override
        public void describeProperty(String name, @Nullable Map<?, ?> map) {
        }

        @Override
        public void describeProperty(String name, @Nullable String value) {
        }

        @Override
        public void describeProperty(String name, @Nullable Long value) {
        }

        @Override
        public void describeProperty(String name, @Nullable Boolean value) {
        }
    }
}
