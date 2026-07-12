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

package org.axonframework.eventsourcing.handler.tracing;

import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.eventstore.AnnotationBasedTagResolver;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.eventsourcing.tracing.EventSourcingTracingSettings;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TracingEventTagsHandlerEnhancerDefinitionTest {

    private static final String PROCESS_SPAN = "EventProcessor.process RoomBooked";

    private TestSpanFactory spanFactory;
    private TracingEventTagsHandlerEnhancerDefinition testSubject;

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        testSubject = new TracingEventTagsHandlerEnhancerDefinition();
    }

    @Nested
    class ActiveSpanEnrichment {

        @Test
        void addsResolvedTagsToTheActiveSpan() {
            // given a context with a TagResolver component and an active handler span
            TagResolver resolver = event -> Set.of(Tag.of("Army", "army-42"));
            ProcessingContext context = StubProcessingContext.withComponents(
                    registry -> registry.registerComponent(TagResolver.class, c -> resolver)
            );
            EventMessage event = new GenericEventMessage(new MessageType("RoomBooked"), "the-payload");
            startProcessSpan(event, context);
            StubHandlingMember<Object> member = new StubHandlingMember<>(EventMessage.class, String.class);

            // when
            testSubject.wrapHandler(member).handle(event, context, null);

            // then
            spanFactory.verifySpanHasAttributeValue(PROCESS_SPAN, "axoniq.event_tag.Army", "army-42");
        }

        @Test
        void resolvesEventTagAnnotationsFromTheHandlersDeclaredPayloadType() {
            // given the framework's annotation-based resolver and a payload declaring an @EventTag member
            ProcessingContext context = StubProcessingContext.withComponents(
                    registry -> registry.registerComponent(TagResolver.class, c -> new AnnotationBasedTagResolver())
            );
            EventMessage event =
                    new GenericEventMessage(new MessageType("RoomBooked"), new TaggedPayload("dwelling-7"));
            startProcessSpan(event, context);
            StubHandlingMember<Object> member = new StubHandlingMember<>(EventMessage.class, TaggedPayload.class);

            // when
            testSubject.wrapHandler(member).handle(event, context, null);

            // then
            spanFactory.verifySpanHasAttributeValue(PROCESS_SPAN, "axoniq.event_tag.Dwelling", "dwelling-7");
        }
    }

    @Nested
    class GracefulDegradation {

        @Test
        void invokesTheDelegateWithoutEnrichmentWhenNoSpanIsActive() {
            // given a context with a TagResolver but no active span (tracing not decorating this invocation)
            ProcessingContext context = StubProcessingContext.withComponents(
                    registry -> registry.registerComponent(TagResolver.class,
                                                           c -> event -> Set.of(Tag.of("Army", "army-42")))
            );
            EventMessage event = new GenericEventMessage(new MessageType("RoomBooked"), "the-payload");
            StubHandlingMember<Object> member = new StubHandlingMember<>(EventMessage.class, String.class);

            // when
            testSubject.wrapHandler(member).handle(event, context, null);

            // then the invocation reaches the delegate and no span was produced
            assertThat(member.handled).isTrue();
        }

        @Test
        void invokesTheDelegateWithoutEnrichmentWhenNoTagResolverIsConfigured() {
            // given a plain context (EmptyApplicationContext - component lookups throw) with an active span
            ProcessingContext context = new StubProcessingContext();
            EventMessage event = new GenericEventMessage(new MessageType("RoomBooked"), "the-payload");
            startProcessSpan(event, context);
            StubHandlingMember<Object> member = new StubHandlingMember<>(EventMessage.class, String.class);

            // when
            testSubject.wrapHandler(member).handle(event, context, null);

            // then no exception propagates and the span carries no tag attributes
            assertThat(member.handled).isTrue();
            spanFactory.verifySpanHasNoAttribute(PROCESS_SPAN, "axoniq.event_tag.Army");
        }

        @Test
        void skipsEnrichmentWhenDisabledViaSettings() {
            // given event-tag enrichment disabled through the EventSourcingTracingSettings component
            ProcessingContext context = StubProcessingContext.withComponents(
                    registry -> {
                        registry.registerComponent(TagResolver.class,
                                                   c -> event -> Set.of(Tag.of("Army", "army-42")));
                        registry.registerComponent(EventSourcingTracingSettings.class,
                                                   c -> new EventSourcingTracingSettings(true, new EventSourcingTracingSettings.SpanAttributesProviders(false)));
                    }
            );
            EventMessage event = new GenericEventMessage(new MessageType("RoomBooked"), "the-payload");
            startProcessSpan(event, context);
            StubHandlingMember<Object> member = new StubHandlingMember<>(EventMessage.class, String.class);

            // when
            testSubject.wrapHandler(member).handle(event, context, null);

            // then
            assertThat(member.handled).isTrue();
            spanFactory.verifySpanHasNoAttribute(PROCESS_SPAN, "axoniq.event_tag.Army");
        }
    }

    @Nested
    class WrappingScope {

        @Test
        void doesNotWrapCommandHandlers() {
            // given
            StubHandlingMember<Object> member = new StubHandlingMember<>(CommandMessage.class, String.class);

            // when / then
            assertThat(testSubject.wrapHandler(member)).isSameAs(member);
        }

        @Test
        void doesNotWrapEventSourcingHandlers() {
            // given an @EventSourcingHandler member (replay hot path; its enclosing span would collect
            // mutually-overwriting tags from every replayed event)
            StubHandlingMember<Object> member =
                    new StubHandlingMember<>(EventMessage.class, String.class, true);

            // when / then
            assertThat(testSubject.wrapHandler(member)).isSameAs(member);
        }
    }

    @Nested
    class ServiceLoaderDiscovery {

        @Test
        void theEnhancerIsDiscoverableThroughTheStandardHandlerEnhancerSpi() {
            // when
            ServiceLoader<HandlerEnhancerDefinition> loader = ServiceLoader.load(HandlerEnhancerDefinition.class);

            // then
            assertThat(loader).anyMatch(definition -> definition instanceof TracingEventTagsHandlerEnhancerDefinition);
        }
    }

    private void startProcessSpan(EventMessage event, ProcessingContext context) {
        Span span = spanFactory.createHandlerSpan(PROCESS_SPAN, event, context);
        span.coverLifecycle(context);
    }

    private record TaggedPayload(@EventTag(key = "Dwelling") String dwellingId) {
    }

    /**
     * Minimal {@link MessageHandlingMember} stub reporting which message type it handles and which payload type its
     * method declares, optionally carrying the {@code EventSourcingHandler.payloadType} handler attribute.
     */
    private static final class StubHandlingMember<T> implements MessageHandlingMember<T> {

        private final Class<? extends Message> handledType;
        private final Class<?> payloadType;
        private final boolean eventSourcingHandler;
        private boolean handled = false;

        private StubHandlingMember(Class<? extends Message> handledType, Class<?> payloadType) {
            this(handledType, payloadType, false);
        }

        private StubHandlingMember(Class<? extends Message> handledType,
                                   Class<?> payloadType,
                                   boolean eventSourcingHandler) {
            this.handledType = handledType;
            this.payloadType = payloadType;
            this.eventSourcingHandler = eventSourcingHandler;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Optional<R> attribute(String attributeKey) {
            if (eventSourcingHandler && "EventSourcingHandler.payloadType".equals(attributeKey)) {
                return (Optional<R>) Optional.of(payloadType);
            }
            return Optional.empty();
        }

        @Override
        public Class<?> payloadType() {
            return payloadType;
        }

        @Override
        public boolean canHandle(Message message, ProcessingContext context) {
            return true;
        }

        @Override
        public boolean canHandleMessageType(Class<? extends Message> messageType) {
            return handledType.isAssignableFrom(messageType);
        }

        @Override
        public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable T target) {
            handled = true;
            return MessageStream.empty();
        }

        @Override
        public <HT> Optional<HT> unwrap(Class<HT> handlerType) {
            return Optional.empty();
        }
    }
}
