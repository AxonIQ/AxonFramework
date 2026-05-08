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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link IgnoredEntriesMessageStream} through the {@link MessageStreamTest} suite.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class IgnoredEntriesMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        Assumptions.assumeTrue(messages.isEmpty(), "EmptyMessageStream ignores entries");
        return MessageStream.fromIterable(messages).ignoreEntries();
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        Assumptions.abort("IgnoredEntriesMessageStream ignores entries");
        return MessageStream.just(message).ignoreEntries();
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        return MessageStream.empty().ignoreEntries().cast();
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages, RuntimeException failure) {
        Assumptions.abort("IgnoredEntriesMessageStream ignores entries");
        return MessageStream.fromIterable(messages).concatWith(MessageStream.failed(failure)).ignoreEntries();
    }

    @Override
    protected Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"),
                                    "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Test
    void shouldProcessMessagesButIgnoresEntries() {
        var processed = new AtomicBoolean(false);
        var messages = List.of(createRandomMessage());

        var testSubject = MessageStream.fromIterable(messages).ignoreEntries();

        var result = testSubject.onNext(e -> processed.set(true)).asCompletableFuture();

        assertTrue(result.isDone());
        assertNull(result.join());
        assertFalse(processed.get());
    }

    @Override
    @Test
    void shouldEmitOriginalExceptionAsFailure() {
        var testSubject = MessageStream.failed(new MockException()).ignoreEntries();

        var actual = testSubject.first().asCompletableFuture();

        assertTrue(actual.isCompletedExceptionally());
        assertInstanceOf(MockException.class, actual.exceptionNow());
    }

    @Test
    void shouldKeepOriginalExceptionAsFailure() {
        var testSubject = MessageStream.failed(new MockException()).ignoreEntries();

        assertTrue(testSubject.error().isPresent());
    }
}