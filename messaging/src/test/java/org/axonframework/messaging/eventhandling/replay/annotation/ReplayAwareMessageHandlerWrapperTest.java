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

package org.axonframework.messaging.eventhandling.replay.annotation;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.HandlerAttributes;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.annotation.EventHandlingMember;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.ReplayToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for {@link ReplayAwareMessageHandlerWrapper} and its inner {@code ReplayBlockingMessageHandlingMember}
 * wrapper.
 *
 * @author Allard Buijze
 * @since 5.0.3.
 */
class ReplayAwareMessageHandlerWrapperTest {

    private ReplayAwareMessageHandlerWrapper testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new ReplayAwareMessageHandlerWrapper();
    }

    @SuppressWarnings("SameParameterValue")
    private <T> MessageHandlingMember<T> createRealHandler(Class<T> targetClass,
                                                           String methodName,
                                                           Class<?>... parameterTypes) {
        try {
            var handlerDefinition = new org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMemberDefinition();
            var parameterResolver = org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory.forClass(
                    targetClass);
            var eventHandlerDefinition = new org.axonframework.messaging.eventhandling.annotation.MethodEventHandlerDefinition();

            return handlerDefinition.createHandler(
                                            targetClass,
                                            targetClass.getDeclaredMethod(methodName, parameterTypes),
                                            parameterResolver,
                                            result -> org.axonframework.messaging.core.MessageStream.fromItems()
                                    ).map(eventHandlerDefinition::wrapHandler)
                                    .orElseThrow(() -> new IllegalArgumentException("Handler creation failed"));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Method not found: " + methodName, e);
        }
    }

    private ProcessingContext createContextWithToken(TrackingToken token) {
        EventMessage message = EventTestUtils.asEventMessage("dummy");
        return StubProcessingContext.forMessage(message)
                                    .withResource(TrackingToken.RESOURCE_KEY, token);
    }

    private ProcessingContext createContextWithoutToken() {
        EventMessage message = EventTestUtils.asEventMessage("dummy");
        return StubProcessingContext.forMessage(message);
    }

    /**
     * Stub EventHandlingMember for testing.
     */
    private static class StubEventHandlingMember implements EventHandlingMember<Object> {

        final AtomicInteger invocationCount = new AtomicInteger(0);
        private final String eventName;
        private final boolean allowReplay;
        private final java.util.Map<String, Object> customAttributes = new java.util.HashMap<>();

        StubEventHandlingMember(String eventName, boolean allowReplay) {
            this.eventName = eventName;
            this.allowReplay = allowReplay;
        }

        void setAttribute(String key, Object value) {
            customAttributes.put(key, value);
        }

        @Override
        public String eventName() {
            return eventName;
        }

        @Override
        public Class<?> payloadType() {
            return Object.class;
        }

        @Override
        public boolean canHandle(@NonNull Message message, @NonNull ProcessingContext context) {
            return message instanceof EventMessage;
        }

        @Override
        public boolean canHandleMessageType(@NonNull Class<? extends Message> messageType) {
            return EventMessage.class.isAssignableFrom(messageType);
        }

        @Deprecated
        private Object handleSync(@NonNull Message message,
                                  @NonNull ProcessingContext context,
                                  @Nullable Object target) {
            throw new UnsupportedOperationException("Use handle() instead");
        }

        @Override
        public MessageStream<?> handle(@NonNull Message message,
                                       @NonNull ProcessingContext context,
                                       @Nullable Object target) {
            invocationCount.incrementAndGet();
            return MessageStream.fromItems();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <HT> Optional<HT> unwrap(Class<HT> handlerType) {
            if (handlerType.isInstance(this)) {
                return Optional.of((HT) this);
            }
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Optional<R> attribute(String attributeKey) {
            if (HandlerAttributes.ALLOW_REPLAY.equals(attributeKey)) {
                return (Optional<R>) Optional.of(allowReplay);
            }
            if (HandlerAttributes.EVENT_NAME.equals(attributeKey)) {
                return (Optional<R>) Optional.of(eventName);
            }
            if (customAttributes.containsKey(attributeKey)) {
                return (Optional<R>) Optional.ofNullable(customAttributes.get(attributeKey));
            }
            return Optional.empty();
        }
    }

    /**
     * Stub CommandHandlingMember for testing non-event handlers.
     */
    private static class StubCommandHandlingMember implements MessageHandlingMember<Object> {

        @Override
        public Class<?> payloadType() {
            return Object.class;
        }

        @Override
        public boolean canHandle(@NonNull Message message, @NonNull ProcessingContext context) {
            return message instanceof CommandMessage;
        }

        @Override
        public boolean canHandleMessageType(@NonNull Class<? extends Message> messageType) {
            return CommandMessage.class.isAssignableFrom(messageType);
        }

        @Deprecated
        private Object handleSync(@NonNull Message message,
                                  @NonNull ProcessingContext context,
                                  @Nullable Object target) {
            throw new UnsupportedOperationException("Use handle() instead");
        }

        @Override
        public MessageStream<?> handle(@NonNull Message message,
                                       @NonNull ProcessingContext context,
                                       @Nullable Object target) {
            return MessageStream.fromItems();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <HT> Optional<HT> unwrap(Class<HT> handlerType) {
            if (handlerType.isInstance(this)) {
                return Optional.of((HT) this);
            }
            return Optional.empty();
        }
    }

    /**
     * Generic wrapper that does NOT implement EventHandlingMember - simulates the bug scenario.
     */
    private static class GenericWrapper<T> extends WrappedMessageHandlingMember<T> {

        GenericWrapper(MessageHandlingMember<T> delegate) {
            super(delegate);
        }
    }

    /**
     * Test class with @AllowReplay(false) at class level.
     */
    @AllowReplay(false)
    static class ClassLevelReplayNotAllowedTest {

        @SuppressWarnings("unused")
        @org.axonframework.messaging.eventhandling.annotation.EventHandler
        void handleEvent(String event) {
        }
    }

    // Helper methods

    /**
     * Test class with @DisallowReplay at class level (equivalent to @AllowReplay(false)).
     */
    @DisallowReplay
    static class ClassLevelDisallowReplayTest {

        @SuppressWarnings("unused")
        @org.axonframework.messaging.eventhandling.annotation.EventHandler
        void handleEvent(String event) {
        }
    }

    /**
     * Test class with @AllowReplay(true) at class level.
     */
    @SuppressWarnings("DefaultAnnotationParam")
    @AllowReplay(true)
    static class ClassLevelReplayAllowedTest {

        @SuppressWarnings("unused")
        @org.axonframework.messaging.eventhandling.annotation.EventHandler
        void handleEvent(String event) {
        }
    }

    // Stub implementations

    /**
     * Test class without @AllowReplay annotation (defaults to true).
     */
    static class NoAnnotationTest {

        @SuppressWarnings("unused")
        @org.axonframework.messaging.eventhandling.annotation.EventHandler
        void handleEvent(String event) {
        }
    }

    /**
     * Test class with method-level @DisallowReplay and @AllowReplay annotations.
     */
    static class MethodLevelDisallowReplayTest {

        @SuppressWarnings("unused")
        @org.axonframework.messaging.eventhandling.annotation.EventHandler
        @DisallowReplay
        void handleWithDisallowReplay(String event) {
        }

        @SuppressWarnings({"unused", "DefaultAnnotationParam"})
        @org.axonframework.messaging.eventhandling.annotation.EventHandler
        @AllowReplay(true)
        void handleWithAllowReplay(String event) {
        }
    }

    @Nested
    class WrapEventHandlers {

        @Test
        void wrapsDirectEventHandlingMemberWhenReplayNotAllowed() {
            // given
            StubEventHandlingMember original = new StubEventHandlingMember("TestEvent", false);

            // when
            MessageHandlingMember<Object> result = testSubject.wrapHandler(original);

            // then
            assertThat(result).isNotSameAs(original);
            assertThat(result.unwrap(EventHandlingMember.class))
                    .hasValue(original);
            assertThat(result.attribute(HandlerAttributes.ALLOW_REPLAY))
                    .hasValue(false);
        }

        @SuppressWarnings("OptionalGetWithoutIsPresent")
        @Test
        void wrapsAlreadyWrappedEventHandlingMemberWhenReplayNotAllowed() {
            // given - EventHandlingMember wrapped by a generic wrapper that doesn't implement EventHandlingMember
            StubEventHandlingMember eventMember = new StubEventHandlingMember("TestEvent", false);
            GenericWrapper<Object> wrappedMember = new GenericWrapper<>(eventMember);

            // when
            MessageHandlingMember<Object> result = testSubject.wrapHandler(wrappedMember);

            // then
            assertThat(result).isNotSameAs(wrappedMember);
            assertThat(result.unwrap(EventHandlingMember.class))
                    .hasValue(eventMember);
            assertThat(result.attribute(HandlerAttributes.ALLOW_REPLAY))
                    .hasValue(false);
            assertThat(result.unwrap(EventHandlingMember.class).get().eventName())
                    .isEqualTo("TestEvent");
        }

        @Test
        void doesNotWrapEventHandlingMemberWhenReplayIsAllowed() {
            // given
            StubEventHandlingMember original = new StubEventHandlingMember("TestEvent", true);

            // when
            MessageHandlingMember<Object> result = testSubject.wrapHandler(original);

            // then
            assertThat(result).isSameAs(original);
        }
    }

    // Test classes for class-level @AllowReplay annotation testing

    @Nested
    class DoNotWrapNonEventHandlers {

        @Test
        void doesNotWrapCommandHandler() {
            // given
            StubCommandHandlingMember original = new StubCommandHandlingMember();

            // when
            MessageHandlingMember<Object> result = testSubject.wrapHandler(original);

            // then
            assertThat(result).isSameAs(original);
        }

        @Test
        void doesNotWrapWrappedCommandHandler() {
            // given
            StubCommandHandlingMember commandMember = new StubCommandHandlingMember();
            GenericWrapper<Object> wrappedMember = new GenericWrapper<>(commandMember);

            // when
            MessageHandlingMember<Object> result = testSubject.wrapHandler(wrappedMember);

            // then
            assertThat(result).isSameAs(wrappedMember);
        }
    }

    @Nested
    class MethodLevelAnnotation {

        @Test
        void wrapsHandlerWhenMethodHasDisallowReplay() {
            // given - Method with @DisallowReplay annotation
            MessageHandlingMember<MethodLevelDisallowReplayTest> handler = createRealHandler(
                    MethodLevelDisallowReplayTest.class, "handleWithDisallowReplay", String.class
            );

            // when
            MessageHandlingMember<MethodLevelDisallowReplayTest> result = testSubject.wrapHandler(handler);

            // then
            assertThat(result).isNotSameAs(handler);
            assertThat(result.attribute(HandlerAttributes.ALLOW_REPLAY))
                    .hasValue(false);
        }

        @Test
        void doesNotWrapHandlerWhenMethodHasAllowReplayTrue() {
            // given - Method with @AllowReplay(true) annotation
            MessageHandlingMember<MethodLevelDisallowReplayTest> handler = createRealHandler(
                    MethodLevelDisallowReplayTest.class, "handleWithAllowReplay", String.class
            );

            // when
            MessageHandlingMember<MethodLevelDisallowReplayTest> result = testSubject.wrapHandler(handler);

            // then
            assertThat(result).isSameAs(handler);
        }
    }

    @Nested
    class ClassLevelAnnotation {

        @Test
        void wrapsHandlerWhenClassHasAllowReplayFalse() {
            // given - Real handler method from class with @AllowReplay(false)
            MessageHandlingMember<ClassLevelReplayNotAllowedTest> handler = createRealHandler(
                    ClassLevelReplayNotAllowedTest.class, "handleEvent", String.class
            );

            // when
            MessageHandlingMember<ClassLevelReplayNotAllowedTest> result = testSubject.wrapHandler(handler);

            // then
            assertThat(result).isNotSameAs(handler);
            assertThat(result.attribute(HandlerAttributes.ALLOW_REPLAY))
                    .hasValue(false);
        }

        @Test
        void wrapsHandlerWhenClassHasDisallowReplay() {
            // given - Real handler method from class with @DisallowReplay (equivalent to @AllowReplay(false))
            MessageHandlingMember<ClassLevelDisallowReplayTest> handler = createRealHandler(
                    ClassLevelDisallowReplayTest.class, "handleEvent", String.class
            );

            // when
            MessageHandlingMember<ClassLevelDisallowReplayTest> result = testSubject.wrapHandler(handler);

            // then
            assertThat(result).isNotSameAs(handler);
            assertThat(result.attribute(HandlerAttributes.ALLOW_REPLAY))
                    .hasValue(false);
        }

        @Test
        void doesNotWrapHandlerWhenClassHasAllowReplayTrue() {
            // given - Real handler method from class with @AllowReplay(true)
            MessageHandlingMember<ClassLevelReplayAllowedTest> handler = createRealHandler(
                    ClassLevelReplayAllowedTest.class, "handleEvent", String.class
            );

            // when
            MessageHandlingMember<ClassLevelReplayAllowedTest> result = testSubject.wrapHandler(handler);

            // then
            assertThat(result).isSameAs(handler);
        }

        @Test
        void doesNotWrapHandlerWhenClassHasNoAnnotation() {
            // given - Real handler method from class without @AllowReplay (defaults to true)
            MessageHandlingMember<NoAnnotationTest> handler = createRealHandler(
                    NoAnnotationTest.class, "handleEvent", String.class
            );

            // when
            MessageHandlingMember<NoAnnotationTest> result = testSubject.wrapHandler(handler);

            // then
            assertThat(result).isSameAs(handler);
        }
    }

    @Nested
    class AttributeDelegation {

        @Test
        void preservesAllowReplayFalseAttribute() {
            // given - Handler with allowReplay = false
            StubEventHandlingMember original = new StubEventHandlingMember("TestEvent", false);
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(original);

            // when - Wrapped handler is created, it should preserve the false value
            Optional<Boolean> allowReplay = wrapped.attribute(HandlerAttributes.ALLOW_REPLAY);

            // then - Wrapper preserves the ALLOW_REPLAY = false attribute
            assertThat(allowReplay).hasValue(false);
        }

        @Test
        void delegatesOtherAttributesToWrappedHandler() {
            // given - Handler with custom attribute
            StubEventHandlingMember original = new StubEventHandlingMember("TestEvent", false);
            original.setAttribute("customAttribute", "customValue");
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(original);

            // when - Request attribute that is NOT ALLOW_REPLAY
            Optional<String> customAttribute = wrapped.attribute("customAttribute");

            // then - Should delegate to wrapped handler
            assertThat(customAttribute).hasValue("customValue");
        }

        @Test
        void delegatesEventNameAttributeToWrappedHandler() {
            // given - Handler with event name
            StubEventHandlingMember original = new StubEventHandlingMember("TestEvent", false);
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(original);

            // when - Request EVENT_NAME attribute
            Optional<String> eventName = wrapped.attribute(HandlerAttributes.EVENT_NAME);

            // then - Should delegate to wrapped handler
            assertThat(eventName).hasValue("TestEvent");
        }

        @Test
        void delegatesUnknownAttributesToWrappedHandler() {
            // given - Handler with custom attribute
            StubEventHandlingMember original = new StubEventHandlingMember("TestEvent", false);
            original.setAttribute("unknownAttribute", 123);
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(original);

            // when - Request unknown attribute
            Optional<Integer> unknownAttribute = wrapped.attribute("unknownAttribute");

            // then - Should delegate to wrapped handler
            assertThat(unknownAttribute).hasValue(123);
        }
    }

    @Nested
    class ReplayBlocking {

        @Test
        void blocksHandlerInvocationDuringReplay() {
            // given
            StubEventHandlingMember original = new StubEventHandlingMember("TestEvent", false);
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(original);
            EventMessage event = EventTestUtils.asEventMessage("test-payload");
            TrackingToken replayToken = ReplayToken.createReplayToken(new GlobalSequenceTrackingToken(100L));
            ProcessingContext context = createContextWithToken(replayToken);

            // when
            MessageStream<?> result = wrapped.handle(event, context, null);

            // then - Handler should not be invoked during replay
            assertThat(original.invocationCount.get()).isEqualTo(0);
            // Stream should be completed normally without errors
            assertThat(result.isCompleted()).isTrue();
            assertThat(result.error()).isEmpty();
        }

        @Test
        void allowsHandlerInvocationWithRegularToken() {
            // given
            StubEventHandlingMember original = new StubEventHandlingMember("TestEvent", false);
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(original);
            EventMessage event = EventTestUtils.asEventMessage("test-payload");
            TrackingToken regularToken = new GlobalSequenceTrackingToken(100L);
            ProcessingContext context = createContextWithToken(regularToken);

            // when
            MessageStream<?> result = wrapped.handle(event, context, null);

            // then - Handler should be invoked with regular token
            assertThat(original.invocationCount.get()).isEqualTo(1);
            // Stream should be completed normally without errors
            assertThat(result.next()).isEmpty();
            assertThat(result.isCompleted()).isTrue();
            assertThat(result.error()).isEmpty();
        }

        @Test
        void allowsHandlerInvocationWithoutToken() {
            // given
            StubEventHandlingMember original = new StubEventHandlingMember("TestEvent", false);
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(original);
            EventMessage event = EventTestUtils.asEventMessage("test-payload");
            ProcessingContext context = createContextWithoutToken();

            // when
            MessageStream<?> result = wrapped.handle(event, context, null);

            // then - Handler should be invoked when no token present
            assertThat(original.invocationCount.get()).isEqualTo(1);
            // Stream should be completed normally without errors
            assertThat(result.next()).isEmpty();
            assertThat(result.isCompleted()).isTrue();
            assertThat(result.error()).isEmpty();
        }

        @Test
        void blocksWrappedHandlerInvocationDuringReplay() {
            // given - EventHandlingMember wrapped by a generic wrapper
            StubEventHandlingMember eventMember = new StubEventHandlingMember("TestEvent", false);
            GenericWrapper<Object> wrappedMember = new GenericWrapper<>(eventMember);
            MessageHandlingMember<Object> replayWrapped = testSubject.wrapHandler(wrappedMember);
            EventMessage event = EventTestUtils.asEventMessage("test-payload");
            TrackingToken replayToken = ReplayToken.createReplayToken(new GlobalSequenceTrackingToken(100L));
            ProcessingContext context = createContextWithToken(replayToken);

            // when
            MessageStream<?> result = replayWrapped.handle(event, context, null);

            // then - Handler should not be invoked during replay
            assertThat(eventMember.invocationCount.get()).isEqualTo(0);
            // Stream should be completed normally without errors
            assertThat(result.isCompleted()).isTrue();
            assertThat(result.error()).isEmpty();
        }
    }
}
