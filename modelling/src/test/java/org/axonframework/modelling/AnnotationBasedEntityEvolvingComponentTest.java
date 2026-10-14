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

package org.axonframework.modelling;

import org.axonframework.common.ClockUtils;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.SourceId;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.annotation.SequenceNumber;
import org.axonframework.messaging.eventhandling.annotation.Timestamp;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.junit.jupiter.api.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector.inspectType;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;
import static org.axonframework.messaging.eventhandling.EventTestUtils.createEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link AnnotationBasedEntityEvolvingComponent}.
 *
 * @author Mateusz Nowak
 * @author Jakob Hatzl
 * @since 5.0.0
 */
class AnnotationBasedEntityEvolvingComponentTest {

    private static final EventConverter converter = new DelegatingEventConverter(new JacksonConverter());
    private static final ClassBasedMessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();
    private static final EntityEvolver<TestState> ENTITY_EVOLVER = new AnnotationBasedEntityEvolvingComponent<>(
            TestState.class,
            converter,
            messageTypeResolver,
            ClasspathParameterResolverFactory.forClass(TestState.class),
            ClasspathHandlerDefinition.forClass(TestState.class)
    );

    @Nested
    class BasicEventHandling {

        @Test
        void mutatesStateOnOriginalInstanceIfEventHandlerDoNotReturnsTheModelType() {
            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-0", state.handledPayloads);
        }

        @Test
        void returnsStateAfterHandlingEvent() {
            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-0", state.handledPayloads);
        }

        @Test
        void handlesSequenceOfEvents() {
            // given
            var state = new TestState();
            EventMessage event0 = createEvent(0);
            var context0 = StubProcessingContext.forMessage(event0, "id", 0, "test");
            EventMessage event1 = createEvent(1);
            var context1 = StubProcessingContext.forMessage(event1, "id", 1, "test");
            EventMessage event2 = createEvent(2);
            var context2 = StubProcessingContext.forMessage(event2, "id", 2, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event0, context0);
            state = ENTITY_EVOLVER.evolve(state, event1, context1);
            state = ENTITY_EVOLVER.evolve(state, event2, context2);

            // then
            assertEquals("null-0-1-2", state.handledPayloads);
            assertEquals(3, state.handledCount);
        }
    }

    @Nested
    class ParameterResolution {

        @Test
        void resolvesMetadata() {
            // given
            var state = new TestState();
            var event = new GenericEventMessage(new MessageType(Integer.class),
                                                0,
                                                Metadata.with("sampleKey", "sampleValue"));
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-sampleValue", state.handledMetadata);
        }

        @Test
        void resolvesSequenceNumber() {
            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-0", state.handledSequences);
        }

        @Test
        void resolvesSources() {
            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-id", state.handledSources);
        }

        @Test
        void resolvesTimestamps() {
            var timestamp = Instant.now();
            ClockUtils.set(Clock.fixed(timestamp, ZoneId.systemDefault()));

            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-" + timestamp, state.handledTimestamps);
        }

        @AfterEach
        void afterEach() {
            ClockUtils.reset();
        }
    }

    @Nested
    class HandlerInvocationRules {

        @Test
        void invokesAllEventHandlersResolvingToTheSameEventName() {
            // given
            var sharedEventType = new MessageType("shared-event");
            MessageTypeResolver sharedTypeResolver = payloadType -> Optional.of(sharedEventType);
            var eventSourcedComponent = new AnnotationBasedEntityEvolvingComponent<>(
                    TestState.class,
                    converter,
                    sharedTypeResolver,
                    ClasspathParameterResolverFactory.forClass(TestState.class),
                    ClasspathHandlerDefinition.forClass(TestState.class)
            );
            var state = new TestState();
            var event = new GenericEventMessage(sharedEventType,
                                                0,
                                                Metadata.with("sampleKey", "sampleValue"));
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = eventSourcedComponent.evolve(state, event, context);

            // then
            assertTrue(state.objectHandlerInvoked);
            assertEquals(2, state.handledCount);
        }

        @Test
        void doNotHandleNotDeclaredEventType() {
            // given
            var eventSourcedComponent = new AnnotationBasedEntityEvolvingComponent<>(
                    HandlingJustStringState.class,
                    converter,
                    messageTypeResolver,
                    ClasspathParameterResolverFactory.forClass(HandlingJustStringState.class),
                    ClasspathHandlerDefinition.forClass(HandlingJustStringState.class)
            );
            var state = new HandlingJustStringState();
            var event = createEvent(0);

            // when
            state = eventSourcedComponent.evolve(state, event, StubProcessingContext.forMessage(event));

            // then
            assertEquals(0, state.handledCount);
        }

        @Test
        void invokesOnlyMostSpecificHandler() {
            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-0", state.handledPayloads);
            assertFalse(state.objectHandlerInvoked);
            assertEquals(1, state.handledCount);
        }
    }

    @Nested
    class RecordSupport {

        @SuppressWarnings("unused")
        private record RecordState(String handledPayloads) {

            private static RecordState empty() {
                return new RecordState("null");
            }

            @EventHandler
            RecordState evolve(
                    Integer payload
            ) {
                return new RecordState(handledPayloads + "-" + payload);
            }
        }

        private static final EntityEvolver<RecordState> ENTITY_EVOLVER = new AnnotationBasedEntityEvolvingComponent<>(
                RecordState.class,
                converter,
                messageTypeResolver,
                ClasspathParameterResolverFactory.forClass(RecordState.class),
                ClasspathHandlerDefinition.forClass(RecordState.class)
        );

        @Test
        void doNotMutateGivenStateIfRecord() {
            // given
            var state = RecordState.empty();
            var event = createEvent(0);

            // when
            ENTITY_EVOLVER.evolve(state, event, StubProcessingContext.forMessage(event));

            // then
            assertEquals("null", state.handledPayloads);
        }

        @Test
        void returnsNewObjectIfRecord() {
            // given
            var state = RecordState.empty();
            var event = createEvent(0);

            // when
            state = ENTITY_EVOLVER.evolve(state, event, StubProcessingContext.forMessage(event));

            // then
            assertEquals("null-0", state.handledPayloads);
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void throwsStateEvolvingExceptionOnExceptionInsideEventHandler() {
            // given
            var testSubject = new AnnotationBasedEntityEvolvingComponent<>(
                    ErrorThrowingState.class,
                    converter,
                    messageTypeResolver,
                    ClasspathParameterResolverFactory.forClass(ErrorThrowingState.class),
                    ClasspathHandlerDefinition.forClass(ErrorThrowingState.class)
            );
            var state = new ErrorThrowingState();
            var event = createEvent(0);

            // when-then
            var exception = assertThrows(StateEvolvingException.class,
                                         () -> testSubject.evolve(state,
                                                                  event,
                                                                  StubProcessingContext.forMessage(event)));
            assertEquals(
                    "Failed to apply event [java.lang.Integer#0.0.1] in order to evolve [class org.axonframework.modelling.AnnotationBasedEntityEvolvingComponentTest$ErrorThrowingState] state",
                    exception.getMessage()
            );
            assertInstanceOf(RuntimeException.class, exception.getCause());
            assertTrue(exception.getCause().getMessage().contains("Simulated error for event: 0"));
        }

        @Test
        void rejectsNullModel() {
            // given
            var event = createEvent(0);

            // when-then
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> ENTITY_EVOLVER.evolve(null, event, StubProcessingContext.forMessage(event)),
                         "Model may not be null");
        }
    }

    private static class TestState {

        private String handledPayloads = "null";
        private String handledMetadata = "null";
        private String handledSequences = "null";
        private String handledSources = "null";
        private String handledTimestamps = "null";
        private int handledCount = 0;
        private boolean objectHandlerInvoked = false;

        @EventHandler
        void handle(
                Object payload
        ) {
            this.objectHandlerInvoked = true;
            this.handledCount++;
        }

        @EventHandler
        void handle(
                Integer payload,
                @MetadataValue("sampleKey") String metadata,
                @SequenceNumber Long sequenceNumber,
                @SourceId String source,
                @Timestamp Instant timestamp
        ) {
            this.handledPayloads = handledPayloads + "-" + payload;
            this.handledMetadata = handledMetadata + "-" + metadata;
            this.handledSequences = handledSequences + "-" + sequenceNumber;
            this.handledSources = handledSources + "-" + source;
            this.handledTimestamps = handledTimestamps + "-" + timestamp;
            this.handledCount++;
        }
    }

    @SuppressWarnings("unused")
    private static class ErrorThrowingState {

        @EventHandler
        public void handle(Integer event) {
            throw new RuntimeException("Simulated error for event: " + event);
        }
    }

    @SuppressWarnings("unused")
    private static class HandlingJustStringState {

        private int handledCount = 0;

        @EventHandler
        void handle(String event) {
            this.handledCount++;
        }
    }

    @Nested
    class MessageTypeResolutionCaching {

        @Test
        void resolvesHandlerPayloadTypesOnlyDuringInitialization() {
            // given - TestState declares two handlers (Object and Integer payloads)
            var resolver = spy(new ClassBasedMessageTypeResolver());
            var evolver = new AnnotationBasedEntityEvolvingComponent<>(
                    TestState.class,
                    converter,
                    resolver,
                    ClasspathParameterResolverFactory.forClass(TestState.class),
                    ClasspathHandlerDefinition.forClass(TestState.class)
            );
            var state = new TestState();
            verify(resolver).resolveOrThrow(Object.class);
            verify(resolver).resolveOrThrow(Integer.class);
            clearInvocations(resolver);

            // when - evolving multiple events of the same type
            for (int sequence = 0; sequence < 3; sequence++) {
                var event = createEvent(sequence);
                var context = StubProcessingContext.forMessage(event, "id", sequence, "test");
                state = evolver.evolve(state, event, context);
            }

            // then - all events are handled, while message types were resolved only when building the handler index
            assertThat(state.handledCount).isEqualTo(3);
            verifyNoInteractions(resolver);
        }
    }

    @Nested
    class EventNameResolution {

        @Test
        void usesResolvedMessageTypeForHandlingAndSupportedEvents() {
            // given
            var resolver = new AnnotationMessageTypeResolver();
            var evolver = new AnnotationBasedEntityEvolvingComponent<>(
                    RenamedEventState.class,
                    converter,
                    resolver,
                    ClasspathParameterResolverFactory.forClass(RenamedEventState.class),
                    ClasspathHandlerDefinition.forClass(RenamedEventState.class)
            );
            var state = new RenamedEventState();
            var eventType = resolver.resolveOrThrow(RenamedEvent.class);
            var event = new GenericEventMessage(eventType, new RenamedEvent());

            // when
            evolver.evolve(state, event, StubProcessingContext.forMessage(event));

            // then
            assertThat(state.handledCount).isEqualTo(1);
            assertThat(evolver.supportedEvents()).containsExactly(eventType.qualifiedName());
        }

        @Test
        void explicitHandlerEventNameTakesPrecedenceOverPayloadType() {
            // given
            var evolver = new AnnotationBasedEntityEvolvingComponent<>(
                    ExplicitNameState.class,
                    converter,
                    messageTypeResolver,
                    ClasspathParameterResolverFactory.forClass(ExplicitNameState.class),
                    ClasspathHandlerDefinition.forClass(ExplicitNameState.class)
            );
            var state = new ExplicitNameState();
            var event = new GenericEventMessage(new MessageType("explicit-event"), "payload");

            // when
            evolver.evolve(state, event, StubProcessingContext.forMessage(event));

            // then
            assertThat(state.handledCount).isEqualTo(1);
            assertThat(evolver.supportedEvents()).containsExactly(event.type().qualifiedName());
        }

        @Event(name = "renamed-event", version = "1")
        private record RenamedEvent() {

        }

        private static class RenamedEventState {

            private int handledCount;

            @EventHandler
            void handle(RenamedEvent event) {
                handledCount++;
            }
        }

        private static class ExplicitNameState {

            private int handledCount;

            @EventHandler(eventName = "explicit-event")
            void handle(String event) {
                handledCount++;
            }
        }
    }

    @Nested
    class PolymorphicEntitySupport {

        // Sealed interface where the ENTITY ITSELF is polymorphic (not state inside)
        sealed interface Course permits InitialCourse, CreatedCourse, PublishedCourse {

        }

        @SuppressWarnings("unused")
        private record InitialCourse() implements Course {

            @EventHandler
            CreatedCourse onCreate(String courseCreatedEvent) {
                // Handler returns a different type of entity (sibling type)
                return new CreatedCourse(courseCreatedEvent);
            }
        }

        @SuppressWarnings("unused")
        private record CreatedCourse(String courseName) implements Course {

            @EventHandler
            PublishedCourse onPublish(Integer coursePublishedEvent) {
                // Handler returns a different type of entity (sibling type)
                return new PublishedCourse(courseName, coursePublishedEvent);
            }
        }

        @SuppressWarnings("unused")
        private record PublishedCourse(String courseName, Integer publishedVersion) implements Course {

        }

        private static final EntityEvolvingComponent<Course> COURSE_EVOLVER =
                new AnnotationBasedEntityEvolvingComponent<>(
                        Course.class,
                        inspectType(
                                Course.class,
                                new AnnotationMessageTypeResolver(),
                                ClasspathParameterResolverFactory.forClass(Course.class),
                                ClasspathHandlerDefinition.forClass(Course.class),
                                Set.of(InitialCourse.class, CreatedCourse.class, PublishedCourse.class)
                        ),
                        converter,
                        messageTypeResolver
                );

        @Test
        void supportedEventsIncludesHandlersFromAllPolymorphicEntityTypes() {
            assertThat(COURSE_EVOLVER.supportedEvents())
                    .containsExactlyInAnyOrder(
                            messageTypeResolver.resolveOrThrow(String.class).qualifiedName(),
                            messageTypeResolver.resolveOrThrow(Integer.class).qualifiedName()
                    );
        }

        @Test
        void evolvesPolymorphicEntityFromInitialToCreatedType() {
            // given
            Course course = new InitialCourse();
            var event = asEventMessage("Introduction to Axon");
            var context = StubProcessingContext.forMessage(event);

            // when
            course = COURSE_EVOLVER.evolve(course, event, context);

            // then
            assertInstanceOf(CreatedCourse.class, course);
            assertEquals("Introduction to Axon", ((CreatedCourse) course).courseName());
        }

        @Test
        void evolvesPolymorphicEntityFromCreatedToPublishedType() {
            // given
            Course course = new CreatedCourse("Introduction to Axon");
            var event = asEventMessage(1);
            var context = StubProcessingContext.forMessage(event);

            // when
            course = COURSE_EVOLVER.evolve(course, event, context);

            // then
            assertInstanceOf(PublishedCourse.class, course);
            assertEquals("Introduction to Axon", ((PublishedCourse) course).courseName());
            assertEquals(1, ((PublishedCourse) course).publishedVersion());
        }

        @Test
        void evolvesPolymorphicEntityThroughMultipleTypeTransitions() {
            // given
            Course course = new InitialCourse();
            var createEvent = asEventMessage("Introduction to Axon");
            var publishEvent = asEventMessage(1);
            var createContext = StubProcessingContext.forMessage(createEvent);
            var publishContext = StubProcessingContext.forMessage(publishEvent);

            // when
            course = COURSE_EVOLVER.evolve(course, createEvent, createContext);
            course = COURSE_EVOLVER.evolve(course, publishEvent, publishContext);

            // then
            assertInstanceOf(PublishedCourse.class, course);
            assertEquals("Introduction to Axon", ((PublishedCourse) course).courseName());
            assertEquals(1, ((PublishedCourse) course).publishedVersion());
        }
    }
}
