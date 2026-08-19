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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;


import org.jspecify.annotations.Nullable;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

/**
 * Test class validating the {@link HandlerComparator}.
 */
class HandlerComparatorTest {

    private MessageHandlingMember<?> stringHandler;
    private MessageHandlingMember<?> objectHandler;
    private MessageHandlingMember<?> longHandler;
    private MessageHandlingMember<?> numberHandler;
    private MessageHandlingMember<?> priorityHandler;
    private MessageHandlingMember<?> stringHandlerReversedOrder;
    private MessageHandlingMember<?> objectHandlerReversedOrder;

    private Comparator<MessageHandlingMember<?>> testSubject;

    @BeforeEach
    void setUp() {
        stringHandler = new StubMessageHandlingMember(String.class, 0);
        objectHandler = new StubMessageHandlingMember(Object.class, 0);
        longHandler = new StubMessageHandlingMember(Long.class, 0);
        numberHandler = new StubMessageHandlingMember(Number.class, 0);
        priorityHandler = new StubMessageHandlingMember(Object.class, 1);
        stringHandlerReversedOrder = new ReversedOrderMessageHandlingMember(String.class, 0);
        objectHandlerReversedOrder = new ReversedOrderMessageHandlingMember(Object.class, 0);

        testSubject = HandlerComparator.instance();
    }

    @RepeatedTest(10)
    void handlerOrderIsDeterministic() {
        List<MessageHandlingMember<?>> handlers = new ArrayList<>(Arrays.asList(
                stringHandler, objectHandler, longHandler, numberHandler, priorityHandler, stringHandlerReversedOrder,
                objectHandlerReversedOrder
        ));
        ArrayList<MessageHandlingMember<?>> handlers1 = new ArrayList<>(handlers);
        ArrayList<MessageHandlingMember<?>> handlers2 = new ArrayList<>(handlers);

        Collections.shuffle(handlers1, ThreadLocalRandom.current());
        Collections.shuffle(handlers2, ThreadLocalRandom.current());

        handlers1.sort(testSubject);
        handlers2.sort(testSubject);

        assertEquals(handlers1, handlers2);
    }

    @Test
    void subclassesBeforeSuperclasses() {
        assertTrue(testSubject.compare(stringHandler, objectHandler) < 0, "String should appear before Object");
        assertTrue(testSubject.compare(objectHandler, stringHandler) > 0, "String should appear before Object");

        assertTrue(testSubject.compare(numberHandler, objectHandler) < 0, "Number should appear before Object");
        assertTrue(testSubject.compare(objectHandler, numberHandler) > 0, "Number should appear before Object");

        assertTrue(testSubject.compare(longHandler, numberHandler) < 0, "Long should appear before Number");
        assertTrue(testSubject.compare(numberHandler, longHandler) > 0, "Long should appear before Number");

        assertTrue(testSubject.compare(longHandler, objectHandler) < 0, "Long should appear before Object");
        assertTrue(testSubject.compare(objectHandler, longHandler) > 0, "Long should appear before Object");
    }

    @SuppressWarnings("EqualsWithItself")
    @Test
    void handlersIsEqualWithItself() {
        assertEquals(0, testSubject.compare(stringHandler, stringHandler));
        assertEquals(0, testSubject.compare(objectHandler, objectHandler));
        assertEquals(0, testSubject.compare(longHandler, longHandler));
        assertEquals(0, testSubject.compare(numberHandler, numberHandler));
        assertEquals(0, testSubject.compare(priorityHandler, priorityHandler));

        assertNotEquals(0, testSubject.compare(stringHandler, objectHandler));
        assertNotEquals(0, testSubject.compare(longHandler, stringHandler));
        assertNotEquals(0, testSubject.compare(numberHandler, stringHandler));
        assertNotEquals(0, testSubject.compare(objectHandler, longHandler));
        assertNotEquals(0, testSubject.compare(objectHandler, numberHandler));
        assertNotEquals(0, testSubject.compare(priorityHandler, numberHandler));
    }

    @Test
    void handlersSortedCorrectly() {
        List<MessageHandlingMember<?>> members = new ArrayList<>(Arrays.asList(objectHandler,
                                                                               numberHandler,
                                                                               stringHandler,
                                                                               longHandler));

        members.sort(this.testSubject);
        assertTrue(members.indexOf(longHandler) < members.indexOf(numberHandler));
        assertEquals(3, members.indexOf(objectHandler));
    }

    @Test
    void notInSameHierarchyUsesPriorityBasedEvaluation() {
        assertTrue(testSubject.compare(priorityHandler, stringHandler) < 0,
                   "priorityHandler should appear before String based on priority");
        assertTrue(testSubject.compare(stringHandler, priorityHandler) > 0,
                   "priorityHandler should appear before String based on priority");
    }

    @Test
    void handlingMembersImplementingReversedOrderHaveThereOrderReversedAmongOneAnother() {
        assertTrue(testSubject.compare(stringHandlerReversedOrder, objectHandler) < 0,
                   "Reversed-order-String handler should appear before Object handler");
        assertTrue(testSubject.compare(objectHandler, stringHandlerReversedOrder) > 0,
                   "Reversed-order-String handler should appear before Object handler");
        assertTrue(testSubject.compare(objectHandlerReversedOrder, stringHandler) < 0,
                   "Reversed-order-Object handler should appear before String handler ");
        assertTrue(testSubject.compare(stringHandler, objectHandlerReversedOrder) > 0,
                   "Reversed-order-Object handler should appear before String handler ");
        assertTrue(testSubject.compare(stringHandlerReversedOrder, objectHandlerReversedOrder) > 0,
                   "Reversed-order-Object handler should appear before reversed-order-String handler");
        assertTrue(testSubject.compare(objectHandlerReversedOrder, stringHandlerReversedOrder) < 0,
                   "Reversed-order-Object handler should appear before reversed-order-String handler");
    }

    private record StubMessageHandlingMember(Class<?> payloadType, int priority)
            implements MessageHandlingMember<Object> {

        @Override
        public boolean canHandle(@NonNull Message message, @NonNull ProcessingContext context) {
            throw new UnsupportedOperationException("Not implemented yet");
        }

        @Override
        public boolean canHandleMessageType(@NonNull Class<? extends Message> messageType) {
            throw new UnsupportedOperationException("Not implemented (yet)");
        }

        @Override
        public @NonNull MessageStream<?> handle(@NonNull Message message, @NonNull ProcessingContext context,
                                       @Nullable Object target) {
            throw new UnsupportedOperationException("Not implemented yet");
        }

        @Override
        public @NonNull <HT> Optional<HT> unwrap(@NonNull Class<HT> handlerType) {
            return Optional.empty();
        }

        @Override
        public @NonNull <R> Optional<R> attribute(@NonNull String attributeKey) {
            return Optional.empty();
        }

        @Override
        public @NonNull String toString() {
            return "Handler {" +
                    payloadType +
                    ", p=" + priority +
                    '}';
        }
    }

    private record ReversedOrderMessageHandlingMember(Class<?> payloadType, int priority)
            implements MessageHandlingMember<Object> {

        @Override
        public boolean canHandle(@NonNull Message message, @NonNull ProcessingContext context) {
            throw new UnsupportedOperationException("Not implemented yet");
        }

        @Override
        public boolean canHandleMessageType(@NonNull Class<? extends Message> messageType) {
            throw new UnsupportedOperationException("Not implemented (yet)");
        }

        @Override
        public @NonNull MessageStream<?> handle(@NonNull Message message, @NonNull ProcessingContext context,
                                       @Nullable Object target) {
            throw new UnsupportedOperationException("Not implemented yet");
        }

        @Override
        public @NonNull <HT> Optional<HT> unwrap(@NonNull Class<HT> handlerType) {
            return Optional.empty();
        }

        @Override
        public @NonNull <R> Optional<R> attribute(@NonNull String attributeKey) {
            //noinspection unchecked
            return "ResultHandler.resultType".equals(attributeKey) ? Optional.of((R) Object.class) : Optional.empty();
        }

        @Override
        public String toString() {
            return "ResultHandler {" +
                    payloadType +
                    ", p=" + priority +
                    '}';
        }
    }
}
