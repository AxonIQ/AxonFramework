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

package org.axonframework.messaging.core.annotation;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.eventhandling.replay.annotation.AllowReplay;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;
import org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptor;
import org.axonframework.messaging.core.interception.annotation.ResultHandler;
import org.junit.jupiter.api.*;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link AnnotatedHandlerAttributes}.
 *
 * @author Steven van Beelen
 */
class AnnotatedHandlerAttributesTest {

    private AnnotatedHandlerAttributes testSubject;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        Method nonMessageHandlingMember = getClass().getMethod("annotatedNonMessageHandler", Object.class);
        testSubject = new AnnotatedHandlerAttributes(nonMessageHandlingMember);
    }

    @Test
    void constructHandlerAttributesForAnnotatedCommandHandler() throws NoSuchMethodException {
        Method messageHandlingMember = getClass().getMethod("annotatedCommandHandler", Object.class);

        Map<String, Object> expected = new HashMap<>();
        expected.put(HandlerAttributes.MESSAGE_TYPE, CommandMessage.class);
        expected.put(HandlerAttributes.PAYLOAD_TYPE, String.class);
        expected.put(HandlerAttributes.COMMAND_NAME, "my-command");
        expected.put(HandlerAttributes.COMMAND_ROUTING_KEY, "my-routing-key");
        expected.put(CommandHandler.class.getSimpleName() + ".payloadType", String.class);

        AnnotatedHandlerAttributes testSubject = new AnnotatedHandlerAttributes(messageHandlingMember);

        assertFalse(testSubject.isEmpty());
        assertTrue(testSubject.contains(HandlerAttributes.MESSAGE_TYPE));
        assertEquals(testSubject.get(HandlerAttributes.MESSAGE_TYPE), CommandMessage.class);
        assertEquals(expected, testSubject.getAll());
    }

    @Test
    void constructHandlerAttributesForAnnotatedAllowReplayAndEventHandler() throws NoSuchMethodException {
        Method messageHandlingMember = getClass().getMethod("annotatedAllowReplayAndEventHandler", Object.class);

        Map<String, Object> expected = new HashMap<>();
        expected.put(HandlerAttributes.EVENT_NAME, "custom-event-name");
        expected.put(HandlerAttributes.ALLOW_REPLAY, true);
        expected.put(HandlerAttributes.MESSAGE_TYPE, EventMessage.class);
        expected.put(HandlerAttributes.PAYLOAD_TYPE, Boolean.class);
        expected.put(EventHandler.class.getSimpleName() + ".payloadType", Boolean.class);

        AnnotatedHandlerAttributes testSubject = new AnnotatedHandlerAttributes(messageHandlingMember);

        assertFalse(testSubject.isEmpty());
        assertTrue(testSubject.contains(HandlerAttributes.ALLOW_REPLAY));
        assertEquals(true, testSubject.get(HandlerAttributes.ALLOW_REPLAY));
        assertEquals(expected, testSubject.getAll());
    }

    /**
     * Added as test since an {@link ExceptionHandler} is meta-annotated with {@link ResultHandler} and {@link
     * MessageHandlerInterceptor}. The former of these is in turn meta-annotated with {@link HasHandlerAttributes},
     * whilst the other is meta-annotated with {@link MessageHandler} (which too is meta-annotated with {@code
     * HasHandlerAttributes}. In such a set up <b>all</b> meta-annotation which are {@code HasHandlerAttributes} should
     * have their attributes returned, which thus should be validated to work.
     */
    @Test
    void constructHandlerAttributesForAnnotatedExceptionHandler() throws NoSuchMethodException {
        Method messageHandlingMember = getClass().getMethod("annotatedExceptionHandler", Object.class);

        Map<String, Object> expected = new HashMap<>();
        expected.put(HandlerAttributes.MESSAGE_TYPE, Message.class);
        expected.put(HandlerAttributes.PAYLOAD_TYPE, Object.class);
        expected.put(MessageHandlerInterceptor.class.getSimpleName() + ".messageType", Message.class);
        expected.put(MessageHandlerInterceptor.class.getSimpleName() + ".payloadType", Object.class);
        expected.put(HandlerAttributes.RESULT_TYPE, Exception.class);
        expected.put(HandlerAttributes.EXCEPTION_RESULT_TYPE, Exception.class);
        expected.put(ExceptionHandler.class.getSimpleName() + ".messageType", Message.class);
        expected.put(ExceptionHandler.class.getSimpleName() + ".payloadType", Object.class);

        AnnotatedHandlerAttributes testSubject = new AnnotatedHandlerAttributes(messageHandlingMember);

        assertFalse(testSubject.isEmpty());
        assertTrue(testSubject.contains(HandlerAttributes.EXCEPTION_RESULT_TYPE));
        assertEquals(testSubject.get(HandlerAttributes.EXCEPTION_RESULT_TYPE), Exception.class);
        assertEquals(expected, testSubject.getAll());
    }

    @Test
    void constructHandlerAttributesForAnnotatedCustomCommandHandler() throws NoSuchMethodException {
        Method messageHandlingMember = getClass().getMethod("annotatedCustomCommandHandler", Object.class);

        Map<String, Object> expected = new HashMap<>();
        expected.put(HandlerAttributes.MESSAGE_TYPE, CommandMessage.class);
        expected.put(HandlerAttributes.PAYLOAD_TYPE, Long.class);
        expected.put(HandlerAttributes.COMMAND_NAME, "custom-custom-name");
        expected.put(HandlerAttributes.COMMAND_ROUTING_KEY, "custom-routing-key");
        expected.put(CommandHandler.class.getSimpleName() + ".payloadType", Long.class);
        expected.put(CustomCommandHandler.class.getSimpleName() + ".additionalAttribute", 42);
        expected.put(CustomCommandHandler.class.getSimpleName() + ".payloadType", Long.class);

        AnnotatedHandlerAttributes testSubject = new AnnotatedHandlerAttributes(messageHandlingMember);

        assertFalse(testSubject.isEmpty());
        assertTrue(testSubject.contains(CustomCommandHandler.class.getSimpleName() + ".additionalAttribute"));
        assertEquals(42, (int) testSubject.get(CustomCommandHandler.class.getSimpleName() + ".additionalAttribute"));
        assertEquals(expected, testSubject.getAll());
    }

    @Test
    @Disabled("TODO #3065")
    void constructHandlerAttributesForAnnotatedCustomCombinedHandlerWithAttributes() throws NoSuchMethodException {
        Method messageHandlingMember =
                getClass().getMethod("annotatedCustomCombinedHandlerWithAttributes", Object.class);

        Map<String, Object> expected = new HashMap<>();
//        expected.put(HandlerAttributes.MESSAGE_TYPE, DeadlineMessage.class);
        expected.put(HandlerAttributes.PAYLOAD_TYPE, Float.class);
        expected.put(CustomCombinedHandlerWithAttributes.class.getSimpleName() + ".additionalAttribute", 42);

        AnnotatedHandlerAttributes testSubject = new AnnotatedHandlerAttributes(messageHandlingMember);

        assertFalse(testSubject.isEmpty());
        assertTrue(testSubject.contains(HandlerAttributes.MESSAGE_TYPE));
//        assertEquals(testSubject.get(HandlerAttributes.MESSAGE_TYPE), DeadlineMessage.class);
        assertEquals(expected, testSubject.getAll());
    }

    @Test
    void constructHandlerAttributesForAnnotatedNonMessageHandler() {
        Map<String, Map<String, Object>> expected = new HashMap<>();

        assertEquals(expected, testSubject.getAll());
    }

    @SuppressWarnings("unused")
    @CommandHandler(
            commandName = "my-command",
            routingKey = "my-routing-key",
            payloadType = String.class
    )
    public void annotatedCommandHandler(Object command) {
        // No-op
    }

    @SuppressWarnings("unused")
    @AllowReplay
    @EventHandler(eventName = "custom-event-name", payloadType = Boolean.class)
    public void annotatedAllowReplayAndEventHandler(Object event) {
        // No-op
    }

    @SuppressWarnings("unused")
    @ExceptionHandler
    public void annotatedExceptionHandler(Object exception) {
        // No-op
    }

    @Retention(RetentionPolicy.RUNTIME)
    @CommandHandler(commandName = "custom-custom-name", routingKey = "custom-routing-key")
    protected @interface CustomCommandHandler {

        int additionalAttribute();

        Class<?> payloadType();
    }

    @SuppressWarnings("unused")
    @CustomCommandHandler(
            additionalAttribute = 42,
            payloadType = Long.class
    )
    public void annotatedCustomCommandHandler(Object command) {
        // No-op
    }

    @HasHandlerAttributes
    @Retention(RetentionPolicy.RUNTIME)
//    @MessageHandler(messageType = DeadlineMessage.class, payloadType = Float.class)
    protected @interface CustomCombinedHandlerWithAttributes {

        int additionalAttribute();
    }

    @SuppressWarnings("unused")
    @CustomCombinedHandlerWithAttributes(additionalAttribute = 42)
    public void annotatedCustomCombinedHandlerWithAttributes(Object command) {
        // No-op
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.CONSTRUCTOR, ElementType.METHOD})
    protected @interface SomeOtherAnnotation {

        long someAttribute();
    }

    @SuppressWarnings("unused")
    @SomeOtherAnnotation(someAttribute = 9001L)
    public void annotatedNonMessageHandler(Object regularParameter) {
        // No-op
    }
}