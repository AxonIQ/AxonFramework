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

package org.axonframework.messaging.eventhandling.annotation;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.SourceId;
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.replay.GenericReplayStatusChanged;
import org.axonframework.messaging.eventhandling.replay.ReplayStatus;
import org.axonframework.messaging.eventhandling.replay.ReplayStatusChanged;
import org.axonframework.messaging.eventhandling.replay.annotation.ReplayStatusChangedHandler;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.core.sequencing.SequentialPolicy.FULL_SEQUENTIAL_POLICY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link AnnotatedEventHandlingComponent}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class AnnotatedEventHandlingComponentTest {

    private static final String AGGREGATE_TYPE = "test";
    private static final String AGGREGATE_IDENTIFIER = "id";
    private final AtomicInteger callCount = new AtomicInteger();
    private TestEventHandler eventHandler;
    private EventHandlingComponent eventHandlingComponent;

    @BeforeEach
    void beforeEach() {
        eventHandler = new TestEventHandler();
        eventHandlingComponent = annotatedEventHandlingComponent(eventHandler);
    }

    @Test
    void subscribesEventHandlerWithEventName() {
        Object annotatedEventHandler = new Object() {
            @SuppressWarnings("unused")
            @EventHandler(eventName = "myEventName")
            public void handle(String event) {
                // Unimportant
            }
        };
        MessageTypeResolver messageTypeResolver = spy(new AnnotationMessageTypeResolver());
        AnnotatedEventHandlingComponent<Object> annotatedComponent = new AnnotatedEventHandlingComponent<>(
                annotatedEventHandler,
                ClasspathParameterResolverFactory.forClass(annotatedEventHandler.getClass()),
                ClasspathHandlerDefinition.forClass(annotatedEventHandler.getClass()),
                messageTypeResolver,
                new DelegatingEventConverter(PassThroughConverter.INSTANCE)
        );

        Set<QualifiedName> supportedEvents = annotatedComponent.supportedEvents();
        assertThat(supportedEvents).hasSize(1);
        assertThat(supportedEvents).contains(new QualifiedName("myEventName"));
        verifyNoInteractions(messageTypeResolver);
    }

    @Test
    void subscribesEventHandlerThroughMessageTypeResolverWhenEventNameIsEmpty() {
        QualifiedName expectedName = new QualifiedName("defaultName");

        Object annotatedEventHandler = new Object() {
            @SuppressWarnings({"unused", "DefaultAnnotationParam"})
            @EventHandler(eventName = "") // Deliberately empty to give control to the MessageTypeResolver
            public void handle(String event) {
                // Unimportant
            }
        };
        AnnotatedEventHandlingComponent<Object> annotatedComponent = new AnnotatedEventHandlingComponent<>(
                annotatedEventHandler,
                ClasspathParameterResolverFactory.forClass(annotatedEventHandler.getClass()),
                ClasspathHandlerDefinition.forClass(annotatedEventHandler.getClass()),
                payloadType -> Optional.of(new MessageType(expectedName)),
                new DelegatingEventConverter(PassThroughConverter.INSTANCE)
        );

        Set<QualifiedName> supportedEvents = annotatedComponent.supportedEvents();
        assertThat(supportedEvents).hasSize(1);
        assertThat(supportedEvents).contains(expectedName);
    }

    @Test
    void supportedEvents() {
        Set<QualifiedName> actual = eventHandlingComponent.supportedEvents();
        Set<QualifiedName> expected = new HashSet<>(Arrays.asList(
                new QualifiedName(Integer.class),
                new QualifiedName(Object.class)
        ));
        assertEquals(expected, actual);
    }

    @Nested
    class BasicEventHandling {

        @Test
        void canMutateStateInEventHandler() {
            // given
            var event = eventMessage(0);

            // when
            var result = eventHandlingComponent.handle(event, messageProcessingContext(event));

            // then
            assertSuccessfulStream(result);
            assertEquals("null-0", eventHandler.handledPayloads);
        }

        @Test
        void returnsEmptyStreamAfterHandlingEvent() {
            // given
            var event = eventMessage(0);

            // when
            var result = eventHandlingComponent.handle(event, messageProcessingContext(event));

            // then
            assertSuccessfulStream(result);
            assertInstanceOf(MessageStream.Empty.class, result);
            assertEquals("null-0", eventHandler.handledPayloads);
        }

        @Test
        void handlesSequenceOfEvents() {
            // when
            EventMessage event0 = eventMessage(0);
            var result1 = eventHandlingComponent.handle(event0, messageProcessingContext(event0));
            EventMessage event1 = eventMessage(1);
            var result2 = eventHandlingComponent.handle(event1, messageProcessingContext(event1));
            EventMessage event2 = eventMessage(2);
            var result3 = eventHandlingComponent.handle(event2, messageProcessingContext(event2));

            // then
            assertSuccessfulStream(result1);
            assertSuccessfulStream(result2);
            assertSuccessfulStream(result3);
            assertEquals("null-0-1-2", eventHandler.handledPayloads);
            assertEquals(3, eventHandler.handledCount);
        }
    }

    @Nested
    class ParameterResolution {

        @Test
        void resolvesMetadata() {
            // given
            var event = eventMessage(0, "sampleValue");

            // when
            var result = eventHandlingComponent.handle(event, messageProcessingContext(event));

            // then
            assertSuccessfulStream(result);
            assertEquals("null-sampleValue", eventHandler.handledMetadata);
        }

        @Test
        void resolvesSequenceNumber() {
            // given
            var event = eventMessage(0);

            // when
            var result = eventHandlingComponent.handle(event, messageProcessingContext(event));

            // then
            assertSuccessfulStream(result);
            assertEquals("null-0", eventHandler.handledSequences);
        }

        @Test
        void resolvesSources() {
            // given
            var event = eventMessage(0);

            // when
            var result = eventHandlingComponent.handle(event, messageProcessingContext(event));

            // then
            assertSuccessfulStream(result);
            assertEquals("null-id", eventHandler.handledSources);
        }

        @Test
        void resolvesTimestamps() {
            var timestamp = Instant.now();
            GenericEventMessage.clock = Clock.fixed(timestamp, ZoneId.systemDefault());

            // given
            var event = eventMessage(0);

            // when
            var result = eventHandlingComponent.handle(event, messageProcessingContext(event));

            // then
            assertSuccessfulStream(result);
            assertEquals("null-" + timestamp, eventHandler.handledTimestamps);
        }

        @AfterEach
        void afterEach() {
            GenericEventMessage.clock = Clock.systemUTC();
        }
    }

    private static void assertSuccessfulStream(MessageStream.Empty<Message> result) {
        assertTrue(result.error().isEmpty());
    }

    @Nested
    class HandlerInvocationRules {

        @Test
        void doNotHandleNotDeclaredEventType() {
            // given
            var eventHandler = new HandlingJustStringEventHandler();
            var eventHandlingComponent = annotatedEventHandlingComponent(eventHandler);
            var event = eventMessage(0);

            // when
            var result = eventHandlingComponent.handle(event, messageProcessingContext(event));

            // then
            assertTrue(result.error().isPresent());
            var exception = result.error().get();
            assertInstanceOf(RuntimeException.class, exception);
            assertEquals(
                    "No handler found for event with name [java.lang.Integer] in component "
                            + "[org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent]",
                    exception.getMessage()
            );
            assertEquals(0, eventHandler.handledCount);
        }

        @Test
        void invokesOnlyMostSpecificHandler() {
            // given
            var event = eventMessage(0);

            // when
            var result = eventHandlingComponent.handle(event, messageProcessingContext(event));

            // then
            assertSuccessfulStream(result);
            assertEquals("null-0", eventHandler.handledPayloads);
            assertFalse(eventHandler.objectHandlerInvoked);
            assertEquals(1, eventHandler.handledCount);
        }

        @Test
        void invokesHandlerWithCustomName() {
            // given
            var eventHandler = new HandlingNamedEventHandler();
            var eventHandlingComponent = annotatedEventHandlingComponent(eventHandler);
            var event = new GenericEventMessage(
                    new MessageType("CustomEventName"),
                    123
            );

            // when
            var result = eventHandlingComponent.handle(event, messageProcessingContext(event));

            // then
            assertSuccessfulStream(result);
            assertEquals(1, eventHandler.handledNamed);
            assertEquals(0, eventHandler.handledNotNamed);
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void returnsFailedMessageStreamIfExceptionThrownInsideEventHandler() {
            // given
            var eventHandler = new ErrorThrowingEventHandler();
            var eventHandlingComponent = annotatedEventHandlingComponent(eventHandler);
            var event = eventMessage(0);

            // when
            var result = eventHandlingComponent.handle(event, messageProcessingContext(event));

            // then
            assertTrue(result.error().isPresent());
            var exception = result.error().get();
            assertInstanceOf(RuntimeException.class, exception);
            assertEquals("Simulated error for event: 0", exception.getMessage());
        }

        @Test
        void rejectsNullEvent() {
            // when-then
            assertThrows(NullPointerException.class,
                         () -> eventHandlingComponent.handle((EventMessage) null, messageProcessingContext(null)),
                         "Event Message may not be null");
        }

        @Test
        void rejectsNullProcessingContext() {
            // when-then
            assertThrows(NullPointerException.class,
                         () -> eventHandlingComponent.handle(eventMessage(0), null),
                         "Processing Context may not be null");
        }
    }

    @Nested
    class SupportedEvents {

        @Test
        void testMethodWithPayload() {
            // given
            var eventHandler = new HandlingJustStringEventHandler();
            var eventHandlingComponent = annotatedEventHandlingComponent(eventHandler);

            // when
            var supportedEvents = eventHandlingComponent.supportedEvents();

            // then
            assertThat(supportedEvents).containsExactlyInAnyOrder(
                    new QualifiedName(String.class)
            );
        }

        @Test
        void testMethodsWithObjectAndPayload() {
            // given
            var eventHandler = new TestEventHandler();
            var eventHandlingComponent = annotatedEventHandlingComponent(eventHandler);

            // when
            var supportedEvents = eventHandlingComponent.supportedEvents();

            // then
            assertThat(supportedEvents).containsExactlyInAnyOrder(
                    new QualifiedName(Object.class),
                    new QualifiedName(Integer.class)
            );
        }
    }

    @Nested
    class SequenceIdentifierFor {

        @Test
        void defaultSequencingPolicyIsFullSequentialIfAggregateIdentifierNotInProcessingContext() {
            // given
            var event = eventMessage(0);

            // when
            var sequenceIdentifier = eventHandlingComponent.sequenceIdentifierFor(event, new StubProcessingContext());

            // then
            assertThat(sequenceIdentifier).isEqualTo(FULL_SEQUENTIAL_POLICY);
        }

        @Test
        void defaultSequencingPolicyIsSequentialPerAggregateIfAggregateIdentifierIsPresentInProcessingContext() {
            // given
            var event = eventMessage(0);

            // when
            var sequenceIdentifier = eventHandlingComponent.sequenceIdentifierFor(event,
                                                                                  messageProcessingContext(event));

            // then
            assertThat(sequenceIdentifier).isEqualTo("id");
        }
    }

    private static EventMessage eventMessage(int seq) {
        return eventMessage(seq, null);
    }

    private static EventMessage eventMessage(int seq, String sampleMetadata) {
        return new GenericEventMessage(
                new MessageType(Integer.class),
                seq,
                sampleMetadata == null ? Metadata.emptyInstance() : Metadata.with("sampleKey", sampleMetadata)
        );
    }

    private static class TestEventHandler {

        private String handledPayloads = "null";
        private String handledMetadata = "null";
        private String handledSequences = "null";
        private String handledSources = "null";
        private String handledTimestamps = "null";
        private int handledCount = 0;
        private boolean objectHandlerInvoked = false;
        private final List<ReplayStatusChanged> replayStatusChanges = new ArrayList<>();

        @EventHandler
        void handle(Object payload) {
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

        @ReplayStatusChangedHandler
        void handle(ReplayStatusChanged statusChange) {
            replayStatusChanges.add(statusChange);
        }
    }

    @Nested
    class GivenAnAnnotatedInterfaceMethod {

        interface I {

            @EventHandler
            void handle(Integer event);
        }

        @Nested
        class WhenImplementedByAnnotedInstanceMethod {

            class T implements I {

                @Override
                @EventHandler
                public void handle(Integer event) {
                    callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @Override
                    @EventHandler
                    public void handle(Integer event) {
                        callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @Override
                    public void handle(Integer event) {
                        callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }
        }

        @Nested
        class WhenImplementedByUnannotedInstanceMethod {

            class T implements I {

                @Override
                public void handle(Integer event) {
                    callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @Override
                    @EventHandler
                    public void handle(Integer event) {
                        callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @Override
                    public void handle(Integer event) {
                        callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }
        }
    }

    @Nested
    class GivenAnUnannotatedInterfaceMethod {

        interface I {

            void handle(Integer event);
        }

        @Nested
        class WhenImplementedByAnnotedInstanceMethod {

            class T implements I {

                @Override
                @EventHandler
                public void handle(Integer event) {
                    callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @Override
                    @EventHandler
                    public void handle(Integer event) {
                        callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @Override
                    public void handle(Integer event) {
                        callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }
        }

        @Nested
        class WhenImplementedByUnannotedInstanceMethod {

            class T implements I {

                @Override
                public void handle(Integer event) {
                    callCount.incrementAndGet();
                }
            }

            @Test
            void shouldNotCallAnything() {
                assertNotCalled(new T());
            }

            @Nested
            class AndOverriddenAndAnnotatedInASubclass {

                class U extends T {

                    @Override
                    @EventHandler
                    public void handle(Integer event) {
                        callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldCallHandlerOnlyOnce() {
                    assertCalledOnlyOnce(new U());
                }
            }

            @Nested
            class AndOverriddenButNotAnnotatedInASubclass {

                class U extends T {

                    @Override
                    public void handle(Integer event) {
                        callCount.incrementAndGet();
                    }
                }

                @Test
                void shouldNotCallAnything() {
                    assertNotCalled(new U());
                }
            }
        }
    }

    @Nested
    class GivenAnAnnotatedInstanceMethod {

        class T {

            @EventHandler
            public void handle(Integer event) {
                callCount.incrementAndGet();
            }
        }

        @Test
        void shouldCallHandlerOnlyOnce() {
            assertCalledOnlyOnce(new T());
        }

        @Nested
        class WhenOverriddenAndAnnotatedInASubclass {

            class U extends T {

                @Override
                @EventHandler
                public void handle(Integer event) {
                    callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }

        @Nested
        class WhenNotOverriddenInSubclass {

            class U extends T {

            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }

        @Nested
        class WhenOverriddenButNotAnnotatedInASubclass {

            class U extends T {

                @Override
                public void handle(Integer event) {
                    callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }
    }

    @Nested
    class GivenAnUnannotatedInstanceMethod {

        class T {

            public void handle(Integer event) {
                callCount.incrementAndGet();
            }
        }

        @Test
        void shouldNotCallAnything() {
            assertNotCalled(new T());
        }

        @Nested
        class WhenOverriddenAndAnnotatedInASubclass {

            class U extends T {

                @Override
                @EventHandler
                public void handle(Integer event) {
                    callCount.incrementAndGet();
                }
            }

            @Test
            void shouldCallHandlerOnlyOnce() {
                assertCalledOnlyOnce(new U());
            }
        }

        @Nested
        class WhenOverriddenButNotAnnotatedInASubclass {

            class U extends T {

                @Override
                public void handle(Integer event) {
                    callCount.incrementAndGet();
                }
            }

            @Test
            void shouldNotCallAnything() {
                assertNotCalled(new U());
            }
        }
    }

    private void assertCalledOnlyOnce(Object handlerInstance) {
        AnnotatedEventHandlingComponent<?> annotatedEventHandlingComponent = annotatedEventHandlingComponent(
                handlerInstance);
        EventMessage event = eventMessage(0);

        annotatedEventHandlingComponent.handle(event, messageProcessingContext(event));

        assertThat(callCount.get()).isEqualTo(1);
    }

    private void assertNotCalled(Object handlerInstance) {
        AnnotatedEventHandlingComponent<?> annotatedEventHandlingComponent = annotatedEventHandlingComponent(
                handlerInstance);
        EventMessage event = eventMessage(0);

        annotatedEventHandlingComponent.handle(event, messageProcessingContext(event));

        assertThat(callCount.get()).isEqualTo(0);
    }

    private static class ErrorThrowingEventHandler {

        @EventHandler
        public void handle(Integer event) {
            throw new RuntimeException("Simulated error for event: " + event);
        }
    }

    private static class HandlingJustStringEventHandler {

        private int handledCount = 0;

        @EventHandler
        void handle(String event) {
            this.handledCount++;
        }
    }

    private static class HandlingNamedEventHandler {

        private int handledNamed = 0;
        private int handledNotNamed = 0;

        @EventHandler(eventName = "CustomEventName")
        void handleNamed() {
            handledNamed++;
        }


        @EventHandler
        void handleNotNamed() {
            handledNotNamed++;
        }
    }

        private @NonNull static AnnotatedEventHandlingComponent<?> annotatedEventHandlingComponent(Object eventHandler) {
        return new AnnotatedEventHandlingComponent<>(
                eventHandler,
                ClasspathParameterResolverFactory.forClass(eventHandler.getClass()),
                ClasspathHandlerDefinition.forClass(eventHandler.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingEventConverter(PassThroughConverter.INSTANCE)
        );
    }

    static @NonNull ProcessingContext messageProcessingContext(EventMessage event) {
        var payload = event.payloadAs(Integer.class);
        return StubProcessingContext
                .withComponent(Converter.class, PassThroughConverter.INSTANCE)
                .withMessage(event)
                .withResource(LegacyResources.AGGREGATE_TYPE_KEY, AGGREGATE_TYPE)
                .withResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY, AGGREGATE_IDENTIFIER)
                .withResource(LegacyResources.AGGREGATE_SEQUENCE_NUMBER_KEY, payload.longValue());
    }

    @Nested
    class ReplayStatusChangedHandling {

        @Test
        void invokesReplayStatusChangeHandlerWhenHandlingReplayStatusChange() {
            ReplayStatusChanged replayChange = new GenericReplayStatusChanged(ReplayStatus.REPLAY, (Object) null);
            ReplayStatusChanged regularChange = new GenericReplayStatusChanged(ReplayStatus.REGULAR, (Object) null);

            eventHandlingComponent.handle(replayChange, StubProcessingContext.forMessage(replayChange));
            eventHandlingComponent.handle(regularChange, StubProcessingContext.forMessage(regularChange));

            assertThat(eventHandler.replayStatusChanges).hasSize(2);
            assertThat(eventHandler.replayStatusChanges.get(0).status()).isEqualTo(ReplayStatus.REPLAY);
            assertThat(eventHandler.replayStatusChanges.get(1).status()).isEqualTo(ReplayStatus.REGULAR);
        }
    }

    @Nested
    class AnnotatedInterceptorHandling {

        private static ProcessingContext simpleContext(EventMessage event) {
            return StubProcessingContext.withComponent(Converter.class, PassThroughConverter.INSTANCE)
                                        .withMessage(event);
        }

        private static void drainStream(MessageStream.Empty<Message> stream) {
            stream.reduce(null, (acc, e) -> null).join();
        }

        @Test
        void beforeInterceptorIsInvokedBeforeEventHandler() {
            // given
            var log = new ArrayList<String>();
            var handler = new Object() {
                @EventHandlerInterceptor
                void intercept() { log.add("interceptor"); }
                @EventHandler
                void handle(Integer payload) { log.add("handler"); }
            };
            var component = annotatedEventHandlingComponent(handler);
            var event = eventMessage(0);

            // when
            component.handle(event, simpleContext(event));

            // then
            assertThat(log).containsExactly("interceptor", "handler");
        }

        @Test
        void beforeInterceptorExceptionBreaksChain() {
            // given
            var log = new ArrayList<String>();
            var handler = new Object() {
                @EventHandlerInterceptor
                void intercept() { throw new RuntimeException("interceptor failed"); }
                @EventHandler
                void handle(Integer payload) { log.add("handler"); }
            };
            var component = annotatedEventHandlingComponent(handler);
            var event = eventMessage(0);

            // when
            var result = component.handle(event, simpleContext(event));

            // then - error propagated, handler never called
            assertThat(result.error()).isPresent();
            assertThat(result.isCompleted()).isTrue();
            assertThat(log).doesNotContain("handler");
        }

        @Test
        void surroundInterceptorCanWrapHandling() {
            // given
            var log = new ArrayList<String>();
            var handler = new Object() {
                @EventHandlerInterceptor
                @SuppressWarnings({"rawtypes", "unchecked"})
                MessageStream<?> intercept(EventMessage event,
                                           MessageHandlerInterceptorChain chain,
                                           ProcessingContext ctx) {
                    log.add("before");
                    MessageStream<?> result = chain.proceed(event, ctx);
                    log.add("after");
                    return result;
                }
                @EventHandler
                void handle(Integer payload) { log.add("handler"); }
            };
            var component = annotatedEventHandlingComponent(handler);
            var event = eventMessage(0);

            // when - surround interceptor runs handler synchronously during handle()
            component.handle(event, simpleContext(event));

            // then
            assertThat(log).containsExactly("before", "handler", "after");
        }

        @Test
        void interceptorCanShortCircuitHandling() {
            // given
            var log = new ArrayList<String>();
            var handler = new Object() {
                @EventHandlerInterceptor
                MessageStream<?> intercept(MessageHandlerInterceptorChain<?> chain) {
                    return MessageStream.empty();
                }
                @EventHandler
                void handle(Integer payload) { log.add("handler"); }
            };
            var component = annotatedEventHandlingComponent(handler);
            var event = eventMessage(0);

            // when
            var result = component.handle(event, simpleContext(event));

            // then - chain was not proceeded; handler was never called
            assertThat(result.isCompleted()).isTrue();
            assertThat(result.error()).isEmpty();
            assertThat(log).doesNotContain("handler");
        }

        @Test
        void multipleInterceptorsRunInOrder() {
            // given - two before-interceptors; alphabetical method name breaks the tie
            var log = new ArrayList<String>();
            var handler = new Object() {
                @EventHandlerInterceptor
                void aFirstInterceptor() { log.add("first"); }
                @EventHandlerInterceptor
                void bSecondInterceptor() { log.add("second"); }
                @EventHandler
                void handle(Integer payload) { log.add("handler"); }
            };
            var component = annotatedEventHandlingComponent(handler);
            var event = eventMessage(0);

            // when
            var result = component.handle(event, simpleContext(event));
            drainStream(result);

            // then
            assertThat(log).containsExactly("first", "second", "handler");
        }

        @Test
        void interceptorFilteredByPayloadTypeIsSkippedForNonMatchingEvents() {
            // given - interceptor restricted to String payloads; component handles Integer events
            var log = new ArrayList<String>();
            var handler = new Object() {
                @EventHandlerInterceptor(payloadType = String.class)
                void interceptStringsOnly() { log.add("interceptor"); }
                @EventHandler
                void handle(Integer payload) { log.add("handler"); }
            };
            var component = annotatedEventHandlingComponent(handler);
            var event = eventMessage(0);

            // when
            var result = component.handle(event, simpleContext(event));
            drainStream(result);

            // then - interceptor was skipped; handler ran normally
            assertThat(log).doesNotContain("interceptor")
                           .contains("handler");
        }

        @Test
        void nonVoidInterceptorWithoutChainParamIsRejected() {
            // given - @EventHandlerInterceptor on a non-void method with no chain parameter
            var handler = new Object() {
                @EventHandlerInterceptor
                String intercept() { return "not void"; }
                @EventHandler
                void handle(Integer payload) {
                    // no-op; component needs a handler but invocation is never tested
                }
            };

            // when / then
            assertThatThrownBy(() -> annotatedEventHandlingComponent(handler))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("declare a parameter of type InterceptorChain");
        }

        @Test
        void exceptionHandlerWithChainParamIsRejected() {
            // given - @ExceptionHandler is a result handler; combining it with a chain parameter is illegal
            var handler = new Object() {
                @ExceptionHandler
                void handleException(Exception e, MessageHandlerInterceptorChain<?> chain) {
                    // no-op; component needs a handler but invocation is never tested
                }
                @EventHandler
                void handle(Integer payload) {
                    // no-op; component needs a handler but invocation is never tested
                }
            };

            // when / then
            assertThatThrownBy(() -> annotatedEventHandlingComponent(handler))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("acting on the invocation result must not declare a parameter of type InterceptorChain");
        }

        @Test
        void asyncBeforeInterceptorDefersProceedingChainUntilFutureCompletes() {
            // given - before-interceptor that returns a pending CompletableFuture
            var log = new ArrayList<String>();
            var interceptorFuture = new CompletableFuture<Void>();
            var handler = new Object() {
                @EventHandlerInterceptor
                CompletableFuture<Void> intercept() { return interceptorFuture; }
                @EventHandler
                void handle(Integer payload) { log.add("handler"); }
            };
            var component = annotatedEventHandlingComponent(handler);
            var event = eventMessage(0);

            // when
            var result = component.handle(event, simpleContext(event));

            // then - handler not yet called while future is pending
            assertThat(log).isEmpty();

            // when - future completes normally
            interceptorFuture.complete(null);

            // then - chain proceeds and handler is called once the stream is consumed
            assertThat(result.reduce(null, (acc, e) -> null)).isDone();
            assertThat(log).containsExactly("handler");
        }

        @Test
        void asyncBeforeInterceptorExceptionPreventsHandlerFromBeingCalled() {
            // given - before-interceptor that returns a pending CompletableFuture
            var log = new ArrayList<String>();
            var interceptorFuture = new CompletableFuture<Void>();
            var handler = new Object() {
                @EventHandlerInterceptor
                CompletableFuture<Void> intercept() { return interceptorFuture; }
                @EventHandler
                void handle(Integer payload) { log.add("handler"); }
            };
            var component = annotatedEventHandlingComponent(handler);
            var event = eventMessage(0);

            // when
            var result = component.handle(event, simpleContext(event));

            // then - handler not called while future is pending
            assertThat(log).isEmpty();

            // when - future completes with an error
            interceptorFuture.completeExceptionally(new RuntimeException("interceptor failed"));

            // then - the error propagates through the stream and the handler is never called
            assertThat(result.reduce(null, (acc, e) -> null)).isCompletedExceptionally();
            assertThat(log).isEmpty();
        }

        @Test
        void beforeInterceptorInvokedWithoutInterceptorChainInContextReturnsFailedStream() {
            // given - access the interceptor member directly, bypassing ChainedMessageHandlerInterceptorMember
            // which normally injects the chain; this simulates invocation outside the proper call chain
            var inspector = AnnotatedHandlerInspector.inspectType(BeforeInterceptorHandlerFixture.class);
            var interceptorMember = inspector.getAllInterceptors()
                                             .get(BeforeInterceptorHandlerFixture.class)
                                             .first();
            var event = eventMessage(0);

            // when - context has no interceptor chain resource
            var result = interceptorMember.handle(event, simpleContext(event), null);

            // then
            assertThat(result.isCompleted()).isTrue();
            assertThat(result.error()).isPresent();
            //noinspection OptionalGetWithoutIsPresent
            assertThat(result.error().get())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No interceptor chain found in context for before-interceptor");
        }

        private static class BeforeInterceptorHandlerFixture {
            @EventHandlerInterceptor
            void intercept() {
                // no-op; interceptor framework exits before the body runs when no chain is in context
            }
            @EventHandler
            void handle(Integer payload) {
                // no-op; handler is never invoked in this test
            }
        }
    }
}