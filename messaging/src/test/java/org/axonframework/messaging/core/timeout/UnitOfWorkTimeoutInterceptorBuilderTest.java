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

import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils;
import org.axonframework.messaging.eventhandling.EventHandler;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.interception.EventMessageHandlerInterceptorChain;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link UnitOfWorkTimeoutInterceptorBuilder}.
 *
 * @author Steven van Beelen
 */
class UnitOfWorkTimeoutInterceptorBuilderTest {

    @AfterEach
    void tearDown() throws InterruptedException {
        //noinspection ResultOfMethodCallIgnored | Awaiting termination to ensure none of the AxonTimeLimitedTask hang
        AxonTaskJanitor.INSTANCE.awaitTermination(250, TimeUnit.MILLISECONDS);
    }

    @Nested
    class EventInterceptor {

        @Test
        void interruptsUnitOfWorkWhenHandlingExceedsTimeout() {
            MessageHandlerInterceptor<EventMessage> testSubject = createTimeoutInterceptor(100).buildEventInterceptor();
            EventHandler sleepingHandler = (event, context) -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return MessageStream.empty();
            };

            CompletableFuture<?> result = executeInUnitOfWork(testSubject, sleepingHandler);

            assertTrue(result.isCompletedExceptionally());
            assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        }

        @Test
        void doesNotInterruptUnitOfWorkWhenHandlingCompletesInTime() {
            MessageHandlerInterceptor<EventMessage> testSubject = createTimeoutInterceptor(500).buildEventInterceptor();
            EventHandler fastHandler = (event, context) -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return MessageStream.empty();
            };

            CompletableFuture<?> result = executeInUnitOfWork(testSubject, fastHandler);

            assertFalse(result.isCompletedExceptionally());
        }

        @Test
        void reusesSameTimeoutTaskAcrossMultipleMessagesInSameUnitOfWork() {
            MessageHandlerInterceptor<EventMessage> testSubject = createTimeoutInterceptor(150).buildEventInterceptor();
            EventHandler partialSleepHandler = (event, context) -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return MessageStream.empty();
            };
            EventMessageHandlerInterceptorChain chain =
                    new EventMessageHandlerInterceptorChain(List.of(testSubject), partialSleepHandler);
            EventMessage first = EventTestUtils.asEventMessage("first");
            EventMessage second = EventTestUtils.asEventMessage("second");

            UnitOfWork uow = UnitOfWorkTestUtils.aUnitOfWork();
            CompletableFuture<?> result = uow.executeWithResult(
                    context -> chain.proceed(first, context)
                                    .first()
                                    .asCompletableFuture()
                                    .thenCompose(ignored -> chain.proceed(second, context).first()
                                                                 .asCompletableFuture())
            );

            assertTrue(result.isCompletedExceptionally());
            assertInstanceOf(AxonTimeoutException.class, result.exceptionNow());
        }

        private CompletableFuture<?> executeInUnitOfWork(MessageHandlerInterceptor<EventMessage> interceptor,
                                                         EventHandler terminalHandler) {
            EventMessageHandlerInterceptorChain chain =
                    new EventMessageHandlerInterceptorChain(List.of(interceptor), terminalHandler);
            EventMessage event = EventTestUtils.asEventMessage("test");

            UnitOfWork uow = UnitOfWorkTestUtils.aUnitOfWork();
            return uow.executeWithResult(context -> chain.proceed(event, context).first().asCompletableFuture());
        }
    }

    private UnitOfWorkTimeoutInterceptorBuilder createTimeoutInterceptor(int timeout) {
        return new UnitOfWorkTimeoutInterceptorBuilder(
                "TestComponent",
                timeout,
                500,
                10,
                AxonTaskJanitor.INSTANCE,
                AxonTaskJanitor.LOGGER
        );
    }
}
