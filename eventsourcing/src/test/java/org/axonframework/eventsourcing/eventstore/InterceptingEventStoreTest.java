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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.common.infra.MockComponentDescriptor;
import org.axonframework.common.util.MockException;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.InterceptingEventBus;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link InterceptingEventStore}.
 *
 * @author Steven van Beelen
 */
@ExtendWith(MockitoExtension.class)
class InterceptingEventStoreTest {

    private static final MessageType TEST_EVENT_TYPE = new MessageType("event");

    private EventStoreTransaction eventStoreTransaction;
    private EventStore eventStore;
    private ProcessingContext processingContext;
    private AtomicInteger interceptorCounterOne;
    private MessageDispatchInterceptor<Message> interceptorOne;
    private AtomicInteger interceptorCounterTwo;
    private MessageDispatchInterceptor<Message> interceptorTwo;

    private InterceptingEventStore testSubject;

    @BeforeEach
    void setUp() {
        eventStoreTransaction = mock(EventStoreTransaction.class);
        eventStore = mock(EventStore.class);
        processingContext = mock(ProcessingContext.class);

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

        testSubject = new InterceptingEventStore(eventStore, List.of(interceptorOne, interceptorTwo));
    }

    @Test
    void dispatchInterceptorsInvokedOnTransactionAppend() {
        when(eventStore.transaction(any())).thenReturn(eventStoreTransaction);

        EventMessage testEvent = new GenericEventMessage(TEST_EVENT_TYPE, "test");
        ProcessingContext testContext = StubProcessingContext.forMessage(testEvent);

        EventStoreTransaction transaction = testSubject.transaction(testContext);

        transaction.appendEvent(testEvent);

        ArgumentCaptor<EventMessage> appendedEvent = ArgumentCaptor.forClass(EventMessage.class);
        verify(eventStore).transaction(testContext);
        verify(eventStoreTransaction).appendEvent(appendedEvent.capture());

        assertThat(appendedEvent.getValue()).isEqualTo(testEvent);
        assertThat(interceptorCounterOne).hasValue(1);
        assertThat(interceptorCounterTwo).hasValue(1);
    }

    @Test
    void dispatchInterceptorsAreInvokedForEveryEventOnTransactionAppend() {
        when(eventStore.transaction(any())).thenReturn(eventStoreTransaction);

        EventMessage firstEvent = new GenericEventMessage(TEST_EVENT_TYPE, "first");
        EventMessage secondEvent = new GenericEventMessage(TEST_EVENT_TYPE, "second");
        EventMessage thirdEvent = new GenericEventMessage(TEST_EVENT_TYPE, "third");
        EventMessage fourthEvent = new GenericEventMessage(TEST_EVENT_TYPE, "fourth");

        EventStoreTransaction firstTransaction = testSubject.transaction(new StubProcessingContext());
        firstTransaction.appendEvent(firstEvent);
        firstTransaction.appendEvent(secondEvent);
        EventStoreTransaction secondTransaction = testSubject.transaction(new StubProcessingContext());
        secondTransaction.appendEvent(thirdEvent);
        secondTransaction.appendEvent(fourthEvent);

        assertThat(interceptorCounterOne.get()).isEqualTo(4);
        assertThat(interceptorCounterTwo.get()).isEqualTo(4);
    }

    @Test
    void delegateTransactionSourceDirectly() {
        when(eventStore.transaction(any())).thenReturn(eventStoreTransaction);

        SourcingCondition testCondition = SourcingCondition.conditionFor(EventCriteria.havingAnyTag());

        testSubject.transaction(new StubProcessingContext())
                   .source(testCondition);

        verify(eventStoreTransaction).source(testCondition, null);
    }

    @Test
    void delegateTransactionSourceWithCallbackDirectly() {
        when(eventStore.transaction(any())).thenReturn(eventStoreTransaction);

        SourcingCondition testCondition = SourcingCondition.conditionFor(EventCriteria.havingAnyTag());
        Consumer<Position> resumePositionCallback = rp -> {};

        testSubject.transaction(new StubProcessingContext())
                   .source(testCondition, resumePositionCallback);

        verify(eventStoreTransaction).source(testCondition, resumePositionCallback);
    }

    @Test
    void delegateTransactionOnAppendDirectly() {
        when(eventStore.transaction(any())).thenReturn(eventStoreTransaction);

        Consumer<EventMessage> testOnAppend = (event) -> {
        };

        testSubject.transaction(new StubProcessingContext())
                   .onAppend(testOnAppend);

        verify(eventStoreTransaction).onAppend(testOnAppend);
    }

    @Test
    void delegateTransactionAppendPositionDirectly() {
        when(eventStore.transaction(any())).thenReturn(eventStoreTransaction);

        testSubject.transaction(new StubProcessingContext())
                   .appendPosition();

        verify(eventStoreTransaction).appendPosition();
    }

    @Test
    void delegateTransactionOverrideAppendConditionDirectly() {
        when(eventStore.transaction(any())).thenReturn(eventStoreTransaction);

        UnaryOperator<AppendCondition> testOverride = condition -> condition;

        testSubject.transaction(new StubProcessingContext())
                   .overrideAppendCondition(testOverride);

        verify(eventStoreTransaction).overrideAppendCondition(testOverride);
    }

    @Test
    void dispatchInterceptorsInvokedOnPublish(@Captor ArgumentCaptor<List<EventMessage>> publishedEvents) {
        EventMessage testEvent = new GenericEventMessage(TEST_EVENT_TYPE, "test");

        CompletableFuture<Void> result =
                testSubject.publish(StubProcessingContext.forMessage(testEvent), testEvent);

        verify(eventStore).publish(any(), publishedEvents.capture());

        assertThat(publishedEvents.getValue()).containsExactly(testEvent);
        assertThat(interceptorCounterOne).hasValue(1);
        assertThat(interceptorCounterTwo).hasValue(1);
        assertThat(result).isDone();
    }

    @Test
    void dispatchInterceptorsAreInvokedForEveryEventOnPublish() throws Exception {
        EventMessage firstEvent = new GenericEventMessage(TEST_EVENT_TYPE, "first");
        EventMessage secondEvent = new GenericEventMessage(TEST_EVENT_TYPE, "second");
        EventMessage thirdEvent = new GenericEventMessage(TEST_EVENT_TYPE, "third");
        EventMessage fourthEvent = new GenericEventMessage(TEST_EVENT_TYPE, "fourth");

        testSubject.publish(null, firstEvent, secondEvent).get();
        testSubject.publish(null, thirdEvent, fourthEvent).get();

        assertThat(interceptorCounterOne.get()).isEqualTo(4);
        assertThat(interceptorCounterTwo.get()).isEqualTo(4);
    }

    @Test
    void exceptionsInDispatchInterceptorReturnFailedStreamOnPublish() {
        EventMessage testEvent = new GenericEventMessage(TEST_EVENT_TYPE, "test");

        MessageDispatchInterceptor<Message> faultyInterceptor = (message, context, chain) -> {
            throw new MockException();
        };
        InterceptingEventStore faultyInterceptingEventStore =
                new InterceptingEventStore(eventStore, List.of(faultyInterceptor));

        CompletableFuture<Void> result = faultyInterceptingEventStore.publish(null, testEvent);

        assertThat(result).isDone();
        assertThat(result).isCompletedExceptionally();
        assertThat(result.exceptionNow()).isInstanceOf(MockException.class);
    }

    @Test
    void delegateOpenStreamDirectly() {
        StreamingCondition testCondition = StreamingCondition.startingFrom(TrackingToken.FIRST);

        testSubject.open(testCondition, processingContext);

        verify(eventStore).open(testCondition, processingContext);
    }

    @Test
    void delegateFirstTokenDirectly() {
        testSubject.firstToken(processingContext);

        verify(eventStore).firstToken(processingContext);
    }

    @Test
    void delegateLatestTokenDirectly() {
        testSubject.latestToken(processingContext);

        verify(eventStore).latestToken(processingContext);
    }

    @Test
    void delegateTokenAtDirectly() {
        Instant testInstant = Instant.now();

        testSubject.tokenAt(testInstant, processingContext);

        verify(eventStore).tokenAt(testInstant, processingContext);
    }

    @Test
    void describeIncludesAllRelevantProperties() {
        MockComponentDescriptor descriptor = new MockComponentDescriptor();

        testSubject.describeTo(descriptor);

        Map<String, Object> describedProperties = descriptor.getDescribedProperties();
        assertThat(describedProperties).size().isEqualTo(3);
        assertThat(describedProperties).containsKey("delegate");
        assertThat(describedProperties.get("delegate")).isEqualTo(eventStore);
        assertThat(describedProperties).containsKey("dispatchInterceptors");
        @SuppressWarnings("unchecked")
        List<MessageDispatchInterceptor<? super Message>> dispatchInterceptors =
                (List<MessageDispatchInterceptor<? super Message>>) describedProperties.get("dispatchInterceptors");
        assertThat(dispatchInterceptors).containsExactly(interceptorOne, interceptorTwo);
        assertThat(describedProperties).containsKey("delegateBus");
        assertThat(describedProperties.get("delegateBus")).isInstanceOf(InterceptingEventBus.class);
    }
}