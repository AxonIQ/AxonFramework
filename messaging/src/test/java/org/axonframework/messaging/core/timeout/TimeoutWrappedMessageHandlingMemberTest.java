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

import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMemberDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.axonframework.messaging.core.annotation.MessageStreamResolverUtils.resolveToStream;
import static org.junit.jupiter.api.Assertions.*;

class TimeoutWrappedMessageHandlingMemberTest {

    private AnnotatedMessageHandlingMemberDefinition handlerDefinition;
    private ParameterResolverFactory parameterResolver;

    @BeforeEach
    void setUp() {
        parameterResolver = ClasspathParameterResolverFactory.forClass(getClass());
        handlerDefinition = new AnnotatedMessageHandlingMemberDefinition();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        AxonTaskJanitor.INSTANCE.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    @Test
    void interruptsMessageHandlingMemberWhenHandlingExceedsTimeout() throws NoSuchMethodException {
        MessageHandlingMember<TestMessageHandler> original = getHandler(TestMessageHandler.class, "handle");
        TimeoutWrappedMessageHandlingMember<TestMessageHandler> wrappedHandler =
                new TimeoutWrappedMessageHandlingMember<>(original, 100, 500, 10);

        EventMessage event = EventTestUtils.asEventMessage("my-message");

        MessageStream<?> result = wrappedHandler.handle(
                event, StubProcessingContext.forMessage(event), new TestMessageHandler()
        );

        assertTrue(result.first().asCompletableFuture().isCompletedExceptionally());
        assertInstanceOf(AxonTimeoutException.class, result.first().asCompletableFuture().exceptionNow());
        assertFalse(Thread.interrupted());
    }

    @Test
    void doesNotInterruptMessageHandlingMemberWhenHandlingCompletesInTime() throws NoSuchMethodException {
        MessageHandlingMember<TestMessageHandler> original = getHandler(TestMessageHandler.class, "handleFast");
        TimeoutWrappedMessageHandlingMember<TestMessageHandler> wrappedHandler =
                new TimeoutWrappedMessageHandlingMember<>(original, 200, 500, 10);

        EventMessage event = EventTestUtils.asEventMessage("my-message");

        MessageStream<?> result = wrappedHandler.handle(
                event, StubProcessingContext.forMessage(event), new TestMessageHandler()
        );

        assertFalse(result.first().asCompletableFuture().isCompletedExceptionally());
        assertFalse(Thread.interrupted());
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
    public static class TestMessageHandler {

        @EventHandler
        public void handle(String message) throws InterruptedException {
            Thread.sleep(500);
        }

        @EventHandler
        public void handleFast(String message) throws InterruptedException {
            Thread.sleep(10);
        }
    }
}
