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

package org.axonframework.messaging.eventhandling;

import org.axonframework.common.infra.MockComponentDescriptor;
import org.axonframework.common.util.MockException;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link InterceptingEventSink}.
 *
 * @author Steven van Beelen
 * @author John Hendrikx
 */
@ExtendWith(MockitoExtension.class)
class InterceptingEventSinkTest {

    private static final MessageType TEST_EVENT_TYPE = new MessageType("event");

    private EventSink eventSink;
    private AtomicInteger interceptorCounterOne;
    private MessageDispatchInterceptor<Message> interceptorOne;
    private AtomicInteger interceptorCounterTwo;
    private MessageDispatchInterceptor<Message> interceptorTwo;

    private InterceptingEventSink testSubject;

    @BeforeEach
    void setUp() {
        eventSink = mock(EventSink.class);

        interceptorCounterOne = new AtomicInteger(0);
        interceptorOne = (message, context, chain) -> {
            interceptorCounterOne.incrementAndGet();
            return chain.proceed(message, context);
        };
        interceptorCounterTwo = new AtomicInteger(0);
        interceptorTwo = (message, context, chain) -> {
            interceptorCounterTwo.incrementAndGet();
            return chain.proceed(message, context);
        };

        testSubject = new InterceptingEventSink(eventSink, List.of(interceptorOne, interceptorTwo));
    }

    @Test
    void dispatchInterceptorsInvokedOnceOnPublishWithEventsInSameOrder(
        @Captor ArgumentCaptor<List<EventMessage>> publishedEvents
    ) {
        EventMessage testEvent1 = new GenericEventMessage(TEST_EVENT_TYPE, "test");
        EventMessage testEvent2 = new GenericEventMessage(TEST_EVENT_TYPE, "test");

        CompletableFuture<Void> result =
                testSubject.publish(StubProcessingContext.forMessage(testEvent1), testEvent1, testEvent2);

        verify(eventSink).publish(any(), publishedEvents.capture());

        assertThat(publishedEvents.getValue()).containsExactly(testEvent1, testEvent2);
        assertThat(interceptorCounterOne).hasValue(2);
        assertThat(interceptorCounterTwo).hasValue(2);
        assertThat(result).isDone();
    }

    @Test
    void dispatchInterceptorsInvokedOnceOnPublishWithEventsInSameOrderEvenWithoutContext(
        @Captor ArgumentCaptor<List<EventMessage>> publishedEvents
    ) {
        EventMessage testEvent1 = new GenericEventMessage(TEST_EVENT_TYPE, "test");
        EventMessage testEvent2 = new GenericEventMessage(TEST_EVENT_TYPE, "test");

        CompletableFuture<Void> result =
                testSubject.publish(null, testEvent1, testEvent2);

        verify(eventSink).publish(any(), publishedEvents.capture());

        assertThat(publishedEvents.getValue()).containsExactly(testEvent1, testEvent2);
        assertThat(interceptorCounterOne).hasValue(2);
        assertThat(interceptorCounterTwo).hasValue(2);
        assertThat(result).isDone();
    }

    @Test
    void dispatchInterceptorsAreInvokedForEveryEvent() throws Exception {
        EventMessage firstEvent = new GenericEventMessage(TEST_EVENT_TYPE, "first");
        EventMessage secondEvent = new GenericEventMessage(TEST_EVENT_TYPE, "second");
        EventMessage thirdEvent = new GenericEventMessage(TEST_EVENT_TYPE, "third");
        EventMessage fourthEvent = new GenericEventMessage(TEST_EVENT_TYPE, "fourth");

        testSubject.publish(null, firstEvent, secondEvent).get(5, TimeUnit.SECONDS);
        testSubject.publish(null, thirdEvent, fourthEvent).get(5, TimeUnit.SECONDS);

        assertThat(interceptorCounterOne.get()).isEqualTo(4);
        assertThat(interceptorCounterTwo.get()).isEqualTo(4);
    }

    @Test
    void exceptionsInDispatchInterceptorReturnFailedStream() {
        EventMessage testEvent = new GenericEventMessage(TEST_EVENT_TYPE, "test");

        MessageDispatchInterceptor<Message> faultyInterceptor = (message, context, chain) -> {
            throw new MockException();
        };
        InterceptingEventSink faultyInterceptingEventSink =
                new InterceptingEventSink(eventSink, List.of(faultyInterceptor));

        CompletableFuture<Void> result = faultyInterceptingEventSink.publish(null, testEvent);

        assertThat(result).isDone();
        assertThat(result).isCompletedExceptionally();
        assertThat(result.exceptionNow()).isInstanceOf(MockException.class);
    }

    @Test
    void describeIncludesAllRelevantProperties() {
        MockComponentDescriptor descriptor = new MockComponentDescriptor();

        testSubject.describeTo(descriptor);

        Map<String, Object> describedProperties = descriptor.getDescribedProperties();
        assertThat(describedProperties).size().isEqualTo(2);
        assertThat(describedProperties).containsKey("delegate");
        assertThat(describedProperties.get("delegate")).isEqualTo(eventSink);
        assertThat(describedProperties).containsKey("dispatchInterceptors");
        @SuppressWarnings("unchecked")
        List<MessageDispatchInterceptor<? super Message>> dispatchInterceptors =
                (List<MessageDispatchInterceptor<? super Message>>) describedProperties.get("dispatchInterceptors");
        assertThat(dispatchInterceptors).containsExactly(interceptorOne, interceptorTwo);
    }
}