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

package org.axonframework.messaging.core;

import org.axonframework.messaging.core.MessageStream.Entry;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class QueueMessageStreamTest extends MessageStreamTest<EventMessage> {

    @Override
    protected MessageStream<EventMessage> completedTestSubject(List<EventMessage> messages) {
        QueueMessageStream<EventMessage> testSubject = new QueueMessageStream<>();
        messages.forEach(m -> testSubject.offer(m, Context.empty()));
        testSubject.seal();
        return testSubject;
    }

    @Override
    protected MessageStream.Single<EventMessage> completedSingleStreamTestSubject(EventMessage message) {
        Assumptions.abort("QueueMessageStream does not support explicit single-item streams");
        return null;
    }

    @Override
    protected MessageStream.Empty<EventMessage> completedEmptyStreamTestSubject() {
        Assumptions.abort("QueueMessageStream does not support explicit zero-item streams");
        return null;
    }

    @Override
    protected QueueMessageStream<EventMessage> uncompletedTestSubject(List<EventMessage> messages,
                                                                              CompletableFuture<Void> completionCallback) {
        QueueMessageStream<EventMessage> testSubject = new QueueMessageStream<>();
        messages.forEach(m -> testSubject.offer(m, Context.empty()));

        completionCallback.whenComplete((r, e) -> {
            if (e != null) {
                testSubject.sealExceptionally(e);
            } else {
                testSubject.seal();
            }
        });
        return testSubject;
    }

    @Override
    protected void publishAdditionalMessage(MessageStream<EventMessage> testSubject,
                                            EventMessage randomMessage) {
        ((QueueMessageStream<EventMessage>) testSubject).offer(randomMessage, Context.empty());
    }

    @Override
    protected MessageStream<EventMessage> failingTestSubject(List<EventMessage> messages, RuntimeException failure) {
        QueueMessageStream<EventMessage> testSubject = new QueueMessageStream<>();
        messages.forEach(m -> testSubject.offer(m, Context.empty()));
        testSubject.sealExceptionally(failure);
        return testSubject;
    }

    @Override
    protected EventMessage createRandomMessage() {
        return new GenericEventMessage(new MessageType("message"),
                                         "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Test
    void shouldRefuseNewItemWhenCapacityHasBeenReached() {
        QueueMessageStream<EventMessage> testSubject = new QueueMessageStream<>(new ArrayBlockingQueue<>(2));

        EventMessage message1 = createRandomMessage();
        EventMessage message2 = createRandomMessage();

        assertTrue(testSubject.offer(message1, Context.empty()));
        assertTrue(testSubject.offer(message2, Context.empty()));
        assertFalse(testSubject.offer(createRandomMessage(), Context.empty()));

        assertSame(message1, testSubject.next().map(MessageStream.Entry::message).orElse(null));

        assertTrue(testSubject.offer(createRandomMessage(), Context.empty()));
        assertFalse(testSubject.offer(createRandomMessage(), Context.empty()));

        assertSame(message2, testSubject.next().map(MessageStream.Entry::message).orElse(null));
    }

    @Test
    void closeDiscardsElementsAndPreventsNewElementsFromBeingAdded() {
        EventMessage msg1 = createRandomMessage();
        EventMessage msg2 = createRandomMessage();
        EventMessage msg3 = createRandomMessage();
        CompletableFuture<Void> completionCallback = new CompletableFuture<>();
        QueueMessageStream<EventMessage> testSubject = uncompletedTestSubject(List.of(msg1),
                                                                              completionCallback);

        assertTrue(testSubject.offer(msg2, Context.empty()));

        /*
         * The consumer is calling close, means the stream will complete,
         * any remaining elements will be discarded and next, peek and
         * hasNextAvailable will no longer indicate the presence of
         * elements.
         */

        testSubject.close();

        assertTrue(testSubject.isCompleted());
        assertFalse(testSubject.hasNextAvailable());
        assertThat(testSubject.next()).isEmpty();
        assertThat(testSubject.peek()).isEmpty();

        assertFalse(testSubject.offer(msg3, Context.empty()));
    }

    @Test
    void consumingTheLastElementMarksTheStreamAsClosed() {
        EventMessage msg1 = createRandomMessage();
        EventMessage msg2 = createRandomMessage();
        EventMessage msg3 = createRandomMessage();
        CompletableFuture<Void> completionCallback = new CompletableFuture<>();
        QueueMessageStream<EventMessage> testSubject = uncompletedTestSubject(List.of(msg1),
                                                                              completionCallback);

        assertTrue(testSubject.offer(msg2, Context.empty()));
        testSubject.seal();
        assertThat(testSubject.next()).map(Entry::message).contains(msg1);

        // the stream is not completed because it still has elements.
        assertFalse(testSubject.isCompleted());
        assertFalse(testSubject.offer(msg3, Context.empty()));

        assertThat(testSubject.next()).map(Entry::message).contains(msg2);
        assertFalse(testSubject.isCompleted());  // not yet completed, because haven't gone past end yet

        assertThat(testSubject.hasNextAvailable()).isFalse();
        assertTrue(testSubject.isCompleted());  // completed as next element was attempted to be accessed

        assertTrue(testSubject.isCompleted());
    }
}