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

import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class CloseCallbackMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(
            List<Message> messages) {
        return new CloseCallbackMessageStream<>(MessageStream.fromIterable(messages), () -> {
        });
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(
            Message message) {
        return CloseCallbackMessageStream.single(MessageStream.just(message), () -> {
        });
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        return CloseCallbackMessageStream.empty(MessageStream.empty(), () -> {
        });
    }

    @Override
    protected MessageStream<Message> failingTestSubject(
            List<Message> messages, RuntimeException failure) {
        return new CloseCallbackMessageStream<>(MessageStream.fromIterable(messages)
                                                             .concatWith(MessageStream.failed(failure)),
                                                () -> {
                                                });
    }

    @Override
    protected Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"),
                                  "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Test
    void closeHandlerIsInvokedAfterConsumingTheLastMessage() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MessageStream<Message> testSubject = new CloseCallbackMessageStream<>(MessageStream.fromItems(
                createRandomMessage(),
                createRandomMessage()),
                                                                              () -> invoked.set(true));

        assertFalse(invoked.get());
        assertTrue(testSubject.next().isPresent());
        assertFalse(invoked.get());
        assertTrue(testSubject.next().isPresent());
        assertFalse(testSubject.next().isPresent());
        assertTrue(invoked.get());
        assertTrue(testSubject.isCompleted());
    }

    @Test
    void closeHandlerIsInvokedAfterConsumingTheLastMessageFromAFailedStream() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MessageStream<Message> testSubject = new CloseCallbackMessageStream<>(MessageStream.fromItems(
                                                                                                   createRandomMessage(),
                                                                                                   createRandomMessage())
                                                                                           .concatWith(MessageStream.failed(
                                                                                                   new MockException())),
                                                                              () -> invoked.set(true));

        assertFalse(invoked.get());
        assertTrue(testSubject.next().isPresent());
        assertFalse(invoked.get());
        assertTrue(testSubject.next().isPresent());
        assertFalse(testSubject.next().isPresent());
        assertTrue(invoked.get());
        assertTrue(testSubject.isCompleted());
    }

    @Test
    void closeHandlerIsInvokedAfterCallingClose() {
        AtomicInteger invoked = new AtomicInteger(0);
        MessageStream<Message> testSubject = new CloseCallbackMessageStream<>(MessageStream.fromItems(
                createRandomMessage(),
                createRandomMessage()),
                                                                              invoked::incrementAndGet);

        assertEquals(0, invoked.get());
        assertTrue(testSubject.next().isPresent());
        assertEquals(0, invoked.get());

        /*
         * The consumer is calling close, means the stream will complete,
         * any remaining elements will be discarded and next, peek and
         * hasNextAvailable will no longer indicate the presence of
         * elements.
         */

        testSubject.close();

        assertEquals(1, invoked.get());
        assertTrue(testSubject.isCompleted());
        assertFalse(testSubject.hasNextAvailable());
        assertThat(testSubject.next()).isEmpty();
        assertThat(testSubject.peek()).isEmpty();

        // closing the stream again should not invoke the close handler again
        testSubject.close();

        assertEquals(1, invoked.get());
    }
}