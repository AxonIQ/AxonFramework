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
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link EmptyMessageStream} through the {@link MessageStreamTest} suite.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 */
class EmptyMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        Assumptions.assumeTrue(messages.isEmpty(), "EmptyMessageStream doesn't support content");
        return MessageStream.empty();
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        Assumptions.abort("EmptyMessageStream doesn't support content");
        return null;
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        return MessageStream.empty();
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages,
                                                        RuntimeException failure) {
        Assumptions.abort("EmptyMessageStream doesn't support failed streams");
        return MessageStream.empty();
    }

    @Override
    protected Message createRandomMessage() {
        Assumptions.abort("EmptyMessageStream doesn't support content");
        return null;
    }

    @Test
    void doesNothingOnErrorContinue() {
        AtomicBoolean invoked = new AtomicBoolean(false);

        CompletableFuture<Entry<Message>> result = MessageStream.empty()
                                                                      .onErrorContinue(e -> {
                                                                          invoked.set(true);
                                                                          return MessageStream.empty();
                                                                      })
                                                                      .first()
                                                                      .asCompletableFuture();
        assertTrue(result.isDone());
        assertNull(result.join());
        assertFalse(invoked.get());
    }

    @Test
    void shouldReturnFailedMessageStreamOnFailingCompletionHandler() {
        RuntimeException expected = new RuntimeException("oops");

        CompletableFuture<Object> result = MessageStream.empty()
                                                        .onComplete(() -> {
                                                            throw expected;
                                                        })
                                                        .first()
                                                        .asCompletableFuture()
                                                        .thenApply(Entry::message);

        assertTrue(result.isCompletedExceptionally());
        assertEquals(expected, result.exceptionNow());
    }

    @Test
    void shouldProvideFutureWithIsDone() {
        var invoked = new AtomicBoolean(false);
        var cf = MessageStream.empty().onComplete(() -> invoked.set(true)).asCompletableFuture();
        assertTrue(cf.isDone());
        assertTrue(invoked.get());
    }

    @Test
    void shouldNotProcessOnNext() {
        var processed = new AtomicBoolean(false);
        var testSubject = MessageStream.empty();

        var result = testSubject.onNext(e -> processed.set(true))
                                .asCompletableFuture();
        assertTrue(result.isDone());
        assertNull(result.join());
        assertFalse(processed.get());
    }
}
