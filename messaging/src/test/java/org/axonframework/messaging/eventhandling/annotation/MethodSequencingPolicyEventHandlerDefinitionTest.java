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

import org.axonframework.common.ObjectUtils;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMemberDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.UnsupportedHandlerException;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.messaging.core.sequencing.MetadataSequencingPolicy;
import org.axonframework.messaging.core.sequencing.PropertySequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequentialPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.replay.annotation.ResetHandler;
import org.junit.jupiter.api.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for {@link MethodSequencingPolicyEventHandlerDefinition} to verify proper wrapping of event handlers with
 * sequencing policy annotation.
 *
 * @author Mateusz Nowak
 */
class MethodSequencingPolicyEventHandlerDefinitionTest {

    private AnnotatedMessageHandlingMemberDefinition handlerDefinition;
    private ParameterResolverFactory parameterResolver;
    private MethodEventHandlerDefinition eventHandlerDefinition;

    private MethodSequencingPolicyEventHandlerDefinition testSubject;

    @BeforeEach
    void setUp() {
        handlerDefinition = new AnnotatedMessageHandlingMemberDefinition();
        parameterResolver = ClasspathParameterResolverFactory.forClass(getClass());
        eventHandlerDefinition = new MethodEventHandlerDefinition();

        testSubject = new MethodSequencingPolicyEventHandlerDefinition();
    }

    @Nested
    class MethodLevelAnnotation {

        @Test
        void methodWithSequentialPolicyAnnotation() {
            MessageHandlingMember<MethodLevelPolicyTest> handler = createHandler(
                    MethodLevelPolicyTest.class, "sequentialPolicyMethod", String.class
            );
            MessageHandlingMember<MethodLevelPolicyTest> wrappedHandler = testSubject.wrapHandler(handler);

            assertNotSame(handler, wrappedHandler);
            SequencingPolicy<? super EventMessage> policy = getSequencingPolicy(wrappedHandler);
            assertEquals(SequentialPolicy.class, policy.getClass());
        }

        @Test
        void methodWithMetadataPolicyAnnotation() {
            MessageHandlingMember<MethodLevelPolicyTest> handler = createHandler(
                    MethodLevelPolicyTest.class, "metadataPolicyMethod", TestEvent.class
            );
            MessageHandlingMember<MethodLevelPolicyTest> wrappedHandler = testSubject.wrapHandler(handler);

            assertNotSame(handler, wrappedHandler);
            SequencingPolicy<? super EventMessage> policy = getSequencingPolicy(wrappedHandler);
            assertEquals(MetadataSequencingPolicy.class, policy.getClass());
        }

        @Test
        void methodWithPropertyPolicyAnnotation() {
            MessageHandlingMember<MethodLevelPolicyTest> handler = createHandler(
                    MethodLevelPolicyTest.class, "propertyPolicyMethod", TestEvent.class
            );
            MessageHandlingMember<MethodLevelPolicyTest> wrappedHandler = testSubject.wrapHandler(handler);

            assertNotSame(handler, wrappedHandler);
            SequencingPolicy<? super EventMessage> policy = getSequencingPolicy(wrappedHandler);
            assertEquals(PropertySequencingPolicy.class, policy.getClass());
        }

        @Test
        void methodWithInvalidParameterCount() {
            assertThrows(UnsupportedHandlerException.class, () -> {
                MessageHandlingMember<MethodLevelPolicyTest> handler = createHandler(
                        MethodLevelPolicyTest.class, "invalidParameterCountMethod", TestEvent.class
                );
                testSubject.wrapHandler(handler);
            });
        }

        @Test
        void methodWithCustomPolicyWithInvalidClassPosition() {
            assertThrows(UnsupportedHandlerException.class, () -> {
                MessageHandlingMember<MethodLevelPolicyTest> handler = createHandler(
                        MethodLevelPolicyTest.class, "invalidClassPositionMethod", TestEvent.class
                );
                testSubject.wrapHandler(handler);
            });
        }
    }

    @Nested
    class ClassLevelAnnotation {

        @Test
        void classLevelAnnotationAppliedToMethod() {
            MessageHandlingMember<ClassLevelPolicyTest> handler = createHandler(
                    ClassLevelPolicyTest.class, "eventHandlerMethod", String.class
            );
            MessageHandlingMember<ClassLevelPolicyTest> wrappedHandler = testSubject.wrapHandler(handler);

            assertNotSame(handler, wrappedHandler);
            SequencingPolicy<? super EventMessage> policy = getSequencingPolicy(wrappedHandler);
            assertEquals(SequentialPolicy.class, policy.getClass());
        }
    }

    @Nested
    class NoAnnotation {

        @Test
        void methodWithoutAnnotationNotWrapped() {
            MessageHandlingMember<NoAnnotationTest> handler =
                    createHandler(NoAnnotationTest.class, "methodWithoutAnnotation", String.class);
            MessageHandlingMember<NoAnnotationTest> wrappedHandler = testSubject.wrapHandler(handler);

            assertSame(handler, wrappedHandler);
        }
    }

    @Nested
    class MethodOverridesClass {

        @Test
        void methodLevelOverridesClassLevel() {
            MessageHandlingMember<MethodOverridesClassTest> handler = createHandler(
                    MethodOverridesClassTest.class, "eventHandlerMethod", String.class
            );
            MessageHandlingMember<MethodOverridesClassTest> wrappedHandler = testSubject.wrapHandler(handler);

            assertNotSame(handler, wrappedHandler);
            SequencingPolicy<? super EventMessage> policy = getSequencingPolicy(wrappedHandler);
            // Should be MetadataSequencingPolicy from method, not SequentialPolicy from class
            assertEquals(MetadataSequencingPolicy.class, policy.getClass());
        }
    }

    @Nested
    class WrappedEventHandler {

        @Test
        void wrapsEventHandlerThatIsAlreadyWrappedByGenericWrapper() {
            // given - Create an event handler with sequencing policy annotation
            MessageHandlingMember<MethodLevelPolicyTest> handler = createHandler(
                    MethodLevelPolicyTest.class, "sequentialPolicyMethod", String.class
            );

            // when - Wrap it with a generic wrapper that doesn't implement EventHandlingMember
            // This simulates another HandlerEnhancerDefinition wrapping the handler before this one runs
            MessageHandlingMember<MethodLevelPolicyTest> genericWrapped = new GenericWrapper<>(handler);
            MessageHandlingMember<MethodLevelPolicyTest> result = testSubject.wrapHandler(genericWrapped);

            // then - Should still wrap and apply sequencing policy despite the generic wrapper
            // CURRENT BUG: The instanceof check fails when the handler is wrapped, so sequencing policy is not applied
            // PROPER FIX: Should use canHandleMessageType(EventMessage.class) instead of instanceof
            //             AND check for HandlerAttributes.SEQUENCING_POLICY attribute instead of unwrapping to Method
            //             This follows the Axon pattern where annotations are translated to attributes via @HasHandlerAttributes
            assertNotSame(genericWrapped, result, "Handler should be wrapped with sequencing policy");
            SequencingPolicy<? super EventMessage> policy = getSequencingPolicy(result);
            assertEquals(SequentialPolicy.class, policy.getClass(),
                         "Sequencing policy should be applied even when handler is wrapped");
        }
    }

    /**
     * Generic wrapper that does NOT implement EventHandlingMember - simulates the bug scenario where another
     * HandlerEnhancerDefinition has already wrapped the handler.
     */
    private static class GenericWrapper<T> extends WrappedMessageHandlingMember<T> {

        GenericWrapper(MessageHandlingMember<T> delegate) {
            super(delegate);
        }
    }

    @Nested
    class ResetHandlerNotWrapped {

        @Test
        void resetHandlerWithClassLevelPolicyNotWrapped() {
            MessageHandlingMember<ClassWithResetHandler> handler = createHandler(
                    ClassWithResetHandler.class, "onReset"
            );
            MessageHandlingMember<ClassWithResetHandler> wrappedHandler = testSubject.wrapHandler(handler);

            // ResetHandler methods should NOT be wrapped with sequencing policy
            assertSame(handler, wrappedHandler);
        }

        @Test
        void resetHandlerWithMethodLevelPolicyNotWrapped() {
            MessageHandlingMember<ResetHandlerWithMethodPolicy> handler = createHandler(
                    ResetHandlerWithMethodPolicy.class, "onReset"
            );
            MessageHandlingMember<ResetHandlerWithMethodPolicy> wrappedHandler = testSubject.wrapHandler(handler);

            // ResetHandler methods should NOT be wrapped with sequencing policy even if annotated
            assertSame(handler, wrappedHandler);
        }
    }

    @Nested
    class ExceptionHandlerNotWrapped {

        @Test
        void exceptionHandlerWithClassLevelPolicyNotWrapped() {
            MessageHandlingMember<EventHandlerWithExceptionHandler> handler = createHandler(
                    EventHandlerWithExceptionHandler.class, "onError", IllegalStateException.class
            );
            MessageHandlingMember<EventHandlerWithExceptionHandler> wrappedHandler = testSubject.wrapHandler(handler);

            // ExceptionHandler methods should NOT be wrapped, as the class-level policy only applies to
            // event payload handlers and cannot be constructed against the exception type
            assertSame(handler, wrappedHandler);
        }

        @Test
        void inspectionOfClassWithPropertyPolicyAndExceptionHandlerSucceeds() {
            // Reproduces issue #4705: inspection failed with "Failed to create SequencingPolicy instance: null"
            assertDoesNotThrow(() -> AnnotatedHandlerInspector.inspectType(EventHandlerWithExceptionHandler.class));
        }
    }

    @Nested
    class MetaAnnotationSupport {

        @Test
        void customEventHandlerAnnotationWithClassLevelPolicyIsWrapped() {
            MessageHandlingMember<CustomEventHandlerWithClassPolicy> handler = createHandler(
                    CustomEventHandlerWithClassPolicy.class, "handleWithCustomAnnotation", String.class
            );
            MessageHandlingMember<CustomEventHandlerWithClassPolicy> wrappedHandler = testSubject.wrapHandler(handler);

            // Custom meta-annotated EventHandler should be wrapped
            assertNotSame(handler, wrappedHandler);
            SequencingPolicy policy = getSequencingPolicy(wrappedHandler);
            assertEquals(SequentialPolicy.class, policy.getClass());
        }
    }

    private static MessageStream<?> returnTypeConverter(Object result) {
        return MessageStream.just(new GenericMessage(new MessageType(ObjectUtils.nullSafeTypeOf(result)), result));
    }

    private SequencingPolicy<? super EventMessage> getSequencingPolicy(MessageHandlingMember<?> wrappedHandler) {
        var handler = (MethodSequencingPolicyEventHandlerDefinition.SequencingPolicyEventMessageHandlingMember<?>) wrappedHandler;
        return handler.sequencingPolicy();
    }

    private <T> MessageHandlingMember<T> createHandler(Class<T> targetClass, String methodName,
                                                       Class<?>... parameterTypes) {
        try {
            return handlerDefinition.createHandler(
                                            targetClass,
                                            targetClass.getDeclaredMethod(methodName, parameterTypes),
                                            parameterResolver,
                                            MethodSequencingPolicyEventHandlerDefinitionTest::returnTypeConverter
                                    )
                                    // Wrapping in an Event Handler ensure an EventHandlingMember instance is given.
                                    .map(member -> eventHandlerDefinition.wrapHandler(member))
                                    .orElseThrow(() -> new IllegalArgumentException("Handler creation failed"));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Method used to build handler does not exist: " + methodName, e);
        }
    }

    // Test class for method-level annotation
    static class MethodLevelPolicyTest {

        @SuppressWarnings({"unused", "DefaultAnnotationParam"})
        @EventHandler
        @org.axonframework.messaging.core.annotation.SequencingPolicy(type = SequentialPolicy.class)
        void sequentialPolicyMethod(String payload) {
        }

        @SuppressWarnings("unused")
        @EventHandler
        @org.axonframework.messaging.core.annotation.SequencingPolicy(
                type = MetadataSequencingPolicy.class,
                parameters = {"userId"}
        )
        void metadataPolicyMethod(TestEvent payload) {
        }

        @SuppressWarnings("unused")
        @EventHandler
        @org.axonframework.messaging.core.annotation.SequencingPolicy(
                type = PropertySequencingPolicy.class,
                parameters = {"aggregateId"}
        )
        void propertyPolicyMethod(TestEvent payload) {
        }

        @SuppressWarnings("unused")
        @EventHandler
        @org.axonframework.messaging.core.annotation.SequencingPolicy(
                type = MetadataSequencingPolicy.class,
                parameters = {"param1", "param2", "param3"}
        )
        void invalidParameterCountMethod(TestEvent payload) {
        }

        @SuppressWarnings("unused")
        @EventHandler
        @org.axonframework.messaging.core.annotation.SequencingPolicy(
                type = InvalidClassPositionPolicy.class,
                parameters = {"someParameter"}
        )
        void invalidClassPositionMethod(TestEvent payload) {
        }
    }

    // Test class for no annotation
    static class NoAnnotationTest {

        @SuppressWarnings("unused")
        @EventHandler
        void methodWithoutAnnotation(String payload) {
        }
    }

    // Test event record with properties for PropertySequencingPolicy
    public record TestEvent(String aggregateId, String eventType, long timestamp) {

    }

    // Test policy with Class parameter in wrong position (should fail)
    static class InvalidClassPositionPolicy implements SequencingPolicy<EventMessage> {

        @SuppressWarnings("unused")
        public InvalidClassPositionPolicy(String parameter, Class<?> payloadClass) {
            // Class parameter is not first - this should cause an error
        }

        @Override
        public java.util.Optional<Object> sequenceIdentifierFor(EventMessage event,
                                                                ProcessingContext context) {
            return java.util.Optional.of("test");
        }
    }

    // Test class with class-level annotation
    @SuppressWarnings("DefaultAnnotationParam")
    @org.axonframework.messaging.core.annotation.SequencingPolicy(type = SequentialPolicy.class)
    static class ClassLevelPolicyTest {

        @SuppressWarnings("unused")
        @EventHandler
        void eventHandlerMethod(String payload) {
        }
    }

    // Test class where method overrides class-level annotation
    @SuppressWarnings("DefaultAnnotationParam")
    @org.axonframework.messaging.core.annotation.SequencingPolicy(type = SequentialPolicy.class)
    static class MethodOverridesClassTest {

        @SuppressWarnings("unused")
        @EventHandler
        @org.axonframework.messaging.core.annotation.SequencingPolicy(
                type = MetadataSequencingPolicy.class,
                parameters = {"override"}
        )
        void eventHandlerMethod(String payload) {
        }
    }

    // Test class with class-level SequencingPolicy and a ResetHandler - the ResetHandler should NOT be wrapped
    @SuppressWarnings("DefaultAnnotationParam")
    @org.axonframework.messaging.core.annotation.SequencingPolicy(type = SequentialPolicy.class)
    static class ClassWithResetHandler {

        @SuppressWarnings("unused")
        @EventHandler
        void eventHandlerMethod(String payload) {
        }

        @SuppressWarnings("unused")
        @ResetHandler
        void onReset() {
        }
    }

    // Test class with method-level SequencingPolicy on a ResetHandler - should NOT be wrapped
    static class ResetHandlerWithMethodPolicy {

        @SuppressWarnings({"unused", "DefaultAnnotationParam"})
        @ResetHandler
        @org.axonframework.messaging.core.annotation.SequencingPolicy(type = SequentialPolicy.class)
        void onReset() {
        }
    }

    // Test event record with a property for the class-level PropertySequencingPolicy
    public record SomethingHappened(String key) {

    }

    // Test class with class-level property-based SequencingPolicy and an ExceptionHandler - the ExceptionHandler
    // should NOT be wrapped, as the exception type does not carry the sequencing property (issue #4705)
    @org.axonframework.messaging.core.annotation.SequencingPolicy(
            type = PropertySequencingPolicy.class,
            parameters = {"key"}
    )
    static class EventHandlerWithExceptionHandler {

        @SuppressWarnings("unused")
        @EventHandler
        void handle(SomethingHappened event) {
        }

        @SuppressWarnings("unused")
        @ExceptionHandler(resultType = IllegalStateException.class)
        void onError(IllegalStateException exception) {
        }
    }

    // Custom annotation meta-annotated with @EventHandler
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @EventHandler @interface CustomEventHandler {

    }

    // Test class with custom meta-annotation and class-level SequencingPolicy
    @SuppressWarnings("DefaultAnnotationParam")
    @org.axonframework.messaging.core.annotation.SequencingPolicy(type = SequentialPolicy.class)
    static class CustomEventHandlerWithClassPolicy {

        @SuppressWarnings("unused")
        @CustomEventHandler
        void handleWithCustomAnnotation(String payload) {
        }
    }
}