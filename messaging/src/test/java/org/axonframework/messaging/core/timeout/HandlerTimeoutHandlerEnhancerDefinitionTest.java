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
package org.axonframework.messaging.core.timeout;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMemberDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MessageHandlerTimeout;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.axonframework.messaging.core.annotation.MessageStreamResolverUtils.resolveToStream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link HandlerTimeoutHandlerEnhancerDefinition}.
 *
 * @author Mitchell Herrijgers
 */
class HandlerTimeoutHandlerEnhancerDefinitionTest {

    private AnnotatedMessageHandlingMemberDefinition handlerDefinition;
    private ParameterResolverFactory parameterResolver;
    private HandlerTimeoutHandlerEnhancerDefinition handlerEnhancerDefinition;

    @BeforeEach
    void setUp() {
        parameterResolver = ClasspathParameterResolverFactory.forClass(getClass());
        handlerDefinition = new AnnotatedMessageHandlingMemberDefinition();

        handlerEnhancerDefinition = new HandlerTimeoutHandlerEnhancerDefinition(new HandlerTimeoutConfiguration(
                new TaskTimeoutSettings(40000, 34000, 4000),
                new TaskTimeoutSettings(30000, 24000, 3000),
                new TaskTimeoutSettings(20000, 14000, 2000)
        ));
    }

    @Test
    void createsCorrectHandlerEnhancerDefinitionForQueryHandlerWithAnnotation() throws NoSuchMethodException {
        MessageHandlingMember<QueryHandlerWithAnnotation> handler =
                getHandler(QueryHandlerWithAnnotation.class, "handle");
        MessageHandlingMember<QueryHandlerWithAnnotation> result = handlerEnhancerDefinition.wrapHandler(handler);

        assertIsWrappedAndAssert(result, 100, 50, 10);

        assertInstanceOf(TimeoutWrappedMessageHandlingMember.class, result);
    }


    @Test
    void createsCorrectHandlerEnhancerDefinitionForQueryHandlerWithoutAnnotation() throws NoSuchMethodException {
        MessageHandlingMember<QueryHandlerWithAnnotation> handler =
                getHandler(QueryHandlerWithAnnotation.class, "handleDefault");
        MessageHandlingMember<QueryHandlerWithAnnotation> result = handlerEnhancerDefinition.wrapHandler(handler);

        assertIsWrappedAndAssert(result, 20000, 14000, 2000);

        assertInstanceOf(TimeoutWrappedMessageHandlingMember.class, result);
    }


    @Test
    void createsCorrectHandlerEnhancerDefinitionForCommandHandlerWithAnnotation() throws NoSuchMethodException {
        MessageHandlingMember<CommandHandlerWithAnnotation> handler =
                getHandler(CommandHandlerWithAnnotation.class, "handle");
        MessageHandlingMember<CommandHandlerWithAnnotation> result = handlerEnhancerDefinition.wrapHandler(handler);

        assertIsWrappedAndAssert(result, 100, 50, 10);

        assertInstanceOf(TimeoutWrappedMessageHandlingMember.class, result);
    }


    @Test
    void createsCorrectHandlerEnhancerDefinitionForCommandHandlerWithoutAnnotation() throws NoSuchMethodException {
        MessageHandlingMember<CommandHandlerWithAnnotation> handler =
                getHandler(CommandHandlerWithAnnotation.class, "handleDefault");
        MessageHandlingMember<CommandHandlerWithAnnotation> result = handlerEnhancerDefinition.wrapHandler(handler);

        assertIsWrappedAndAssert(result, 30000, 24000, 3000);

        assertInstanceOf(TimeoutWrappedMessageHandlingMember.class, result);
    }


    @Test
    void createsCorrectHandlerEnhancerDefinitionForEventHandlerWithAnnotation() throws NoSuchMethodException {
        MessageHandlingMember<EventHandlerWithAnnotation> handler =
                getHandler(EventHandlerWithAnnotation.class, "handle");
        MessageHandlingMember<EventHandlerWithAnnotation> result = handlerEnhancerDefinition.wrapHandler(handler);

        assertIsWrappedAndAssert(result, 100, 50, 10);

        assertInstanceOf(TimeoutWrappedMessageHandlingMember.class, result);
    }


    @Test
    void createsCorrectHandlerEnhancerDefinitionForEventHandlerWithoutAnnotation() throws NoSuchMethodException {
        MessageHandlingMember<EventHandlerWithAnnotation> handler =
                getHandler(EventHandlerWithAnnotation.class, "handleDefault");
        MessageHandlingMember<EventHandlerWithAnnotation> result = handlerEnhancerDefinition.wrapHandler(handler);

        assertIsWrappedAndAssert(result, 40000, 34000, 4000);

        assertInstanceOf(TimeoutWrappedMessageHandlingMember.class, result);
    }

    private void assertIsWrappedAndAssert(MessageHandlingMember<?> handler, int timeout, int warningThreshold,
                                          int warningInterval) {
        assertInstanceOf(TimeoutWrappedMessageHandlingMember.class, handler);

        TimeoutWrappedMessageHandlingMember<?> castResult = (TimeoutWrappedMessageHandlingMember<?>) handler;
        assertEquals(timeout, castResult.getTimeout());
        assertEquals(warningThreshold, castResult.getWarningThreshold());
        assertEquals(warningInterval, castResult.getWarningInterval());
    }

    private <T> MessageHandlingMember<T> getHandler(Class<T> targetClass,
                                                    String methodName) throws NoSuchMethodException {
        Optional<MessageHandlingMember<T>> optionalHandler = handlerDefinition.createHandler(
                targetClass,
                targetClass.getDeclaredMethod(methodName, String.class),
                parameterResolver,
                result -> resolveToStream(result, new ClassBasedMessageTypeResolver())
        );
        assertTrue(optionalHandler.isPresent());
        return optionalHandler.get();
    }

    @SuppressWarnings("unused")
    static class QueryHandlerWithAnnotation {

        @MessageHandlerTimeout(timeoutMs = 100, warningThresholdMs = 50, warningIntervalMs = 10)
        @QueryHandler
        public void handle(String message) {
        }

        @QueryHandler
        public void handleDefault(String message) {
        }
    }

    @SuppressWarnings("unused")
    static class EventHandlerWithAnnotation {

        @MessageHandlerTimeout(timeoutMs = 100, warningThresholdMs = 50, warningIntervalMs = 10)
        @EventHandler
        public void handle(String message) {
        }

        @EventHandler
        public void handleDefault(String message) {
        }
    }

    @SuppressWarnings("unused")
    static class CommandHandlerWithAnnotation {

        @MessageHandlerTimeout(timeoutMs = 100, warningThresholdMs = 50, warningIntervalMs = 10)
        @CommandHandler
        public void handle(String message) {
        }

        @CommandHandler
        public void handleDefault(String message) {
        }
    }
}