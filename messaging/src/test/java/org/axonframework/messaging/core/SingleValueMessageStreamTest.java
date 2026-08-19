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

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link SingleValueMessageStream} through the {@link MessageStreamTest} suite.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 */
class SingleValueMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        Assumptions.assumeTrue(messages.size() == 1, "SingleValueMessageStream only supports a single value");
        return MessageStream.just(messages.getFirst());
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        return MessageStream.just(message);
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        return (MessageStream.Empty<Message>) MessageStream.fromFuture(CompletableFuture.completedFuture(null));
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages,
                                                        RuntimeException failure) {
        Assumptions.assumeTrue(messages.isEmpty(),
                               "SingleValueMessageStream only supports failures without regular values");
        return MessageStream.fromFuture(CompletableFuture.failedFuture(failure));
    }

    @Override
    protected Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"),
                                  "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Test
    void shouldReturnNextItemOnceWhenFutureCompletes() {
        CompletableFuture<MessageStream.Entry<Message>> future = new CompletableFuture<>();
        SingleValueMessageStream<Message> testSubject = new SingleValueMessageStream<>(future);

        assertFalse(testSubject.hasNextAvailable());
        assertFalse(testSubject.next().isPresent());

        future.complete(new SimpleEntry<>(createRandomMessage()));

        assertTrue(testSubject.hasNextAvailable());
        assertTrue(testSubject.next().isPresent());
        assertFalse(testSubject.hasNextAvailable());
        assertFalse(testSubject.next().isPresent());
    }

    @Test
    void shouldReturnBeNotCompletedIfConstructedFromCompletedFuture() {
        CompletableFuture<MessageStream.Entry<Message>> future = CompletableFuture.completedFuture(new SimpleEntry<>(
                createRandomMessage()));
        SingleValueMessageStream<Message> testSubject = new SingleValueMessageStream<>(future);
        assertFalse(testSubject.isCompleted());
        assertTrue(testSubject.hasNextAvailable());
    }

    @Test
    void shouldReturnCompletedIfConstructedFromCompletedFutureWithNoValue() {
        CompletableFuture<Message> future = CompletableFuture.completedFuture(null);
        MessageStream<Message> testSubject = MessageStream.fromFuture(future);
        assertTrue(testSubject.isCompleted());
        assertFalse(testSubject.hasNextAvailable());
    }


    @Test
    void shouldPropagateErrorWhenFutureFailed() {
        CompletableFuture<MessageStream.Entry<Message>> future = new CompletableFuture<>();
        SingleValueMessageStream<Message> testSubject = new SingleValueMessageStream<>(future);

        assertFalse(testSubject.hasNextAvailable());
        assertFalse(testSubject.next().isPresent());

        RuntimeException expected = new RuntimeException("Expected");
        future.completeExceptionally(expected);

        assertFalse(testSubject.hasNextAvailable());
        assertFalse(testSubject.next().isPresent());
        assertTrue(testSubject.isCompleted());
        assertTrue(testSubject.error().isPresent());
        assertEquals(testSubject.error().get(), expected);
    }

    @Test
    void shouldInvokeSetCallbackWhenFutureCompletes() {
        CompletableFuture<MessageStream.Entry<Message>> future = new CompletableFuture<>();
        SingleValueMessageStream<Message> testSubject = new SingleValueMessageStream<>(future);

        AtomicBoolean invoked = new AtomicBoolean(false);
        testSubject.setCallback(() -> invoked.set(true));

        assertFalse(invoked.get());
        future.complete(new SimpleEntry<>(createRandomMessage()));

        assertTrue(invoked.get());
    }

    @Test
    void shouldInvokeSetCallbackWhenFutureFails() {
        CompletableFuture<MessageStream.Entry<Message>> future = new CompletableFuture<>();
        SingleValueMessageStream<Message> testSubject = new SingleValueMessageStream<>(future);
        AtomicBoolean invoked = new AtomicBoolean(false);

        testSubject.setCallback(() -> invoked.set(true));

        assertFalse(invoked.get());

        future.completeExceptionally(new RuntimeException("Expected"));

        assertTrue(invoked.get());
    }

    @Test
    void closeCancelsTheCompletableFuture() {
        CompletableFuture<MessageStream.Entry<Message>> future = new CompletableFuture<>();
        SingleValueMessageStream<Message> testSubject = new SingleValueMessageStream<>(future);

        testSubject.close();

        assertTrue(future.isCancelled());
    }

    @Nested
    class NullEntryHandling {

        @Test
        void entryConstructorRejectsNullEntry() {
            // when / then
            assertThatThrownBy(() -> new SingleValueMessageStream<>((MessageStream.Entry<Message>) null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void futureConstructorCompletingWithNullPutsStreamInErrorState() {
            // given
            CompletableFuture<MessageStream.Entry<Message>> future = new CompletableFuture<>();
            SingleValueMessageStream<Message> testSubject = new SingleValueMessageStream<>(future);

            // when
            future.complete(null);

            // then
            assertThat(testSubject.hasNextAvailable()).isFalse();
            assertThat(testSubject.isCompleted()).isTrue();
            assertThat(testSubject.error()).isPresent();
            assertThat(testSubject.error().get()).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class WhenAllElementsConsumed {

        SingleValueMessageStream<Message> ms = new SingleValueMessageStream<>(new SimpleEntry<>(createRandomMessage()));

        @BeforeEach
        void beforeEach() {
            assertThat(ms.hasNextAvailable()).isTrue();
            assertThat(ms.peek()).isNotEmpty();
            assertThat(ms.next()).isNotEmpty();

            /*
             * Expect that the stream has not completed at this point. Streams
             * should never complete before returning their last element, even
             * if they know it was the last element.
             */

            assertThat(ms.isCompleted()).isFalse();
        }

        @Test
        void ensureStreamCompletesAfterHasNextAvailableCall() {
            assertThat(ms.hasNextAvailable()).isFalse();
            assertThat(ms.isCompleted()).isTrue();
        }

        @Test
        void ensureStreamCompletesAfterNextCall() {
            assertThat(ms.next()).isEmpty();
            assertThat(ms.isCompleted()).isTrue();
        }

        @Test
        void ensureStreamCompletesAfterPeekCall() {
            assertThat(ms.peek()).isEmpty();
            assertThat(ms.isCompleted()).isTrue();
        }
    }
}