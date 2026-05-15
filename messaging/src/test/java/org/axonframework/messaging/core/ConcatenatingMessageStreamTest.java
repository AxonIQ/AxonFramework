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
import org.mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link ConcatenatingMessageStream} through the {@link MessageStreamTest} suite.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @author Simon Zambrovski
 */
class ConcatenatingMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        if (messages.isEmpty()) {
            return new ConcatenatingMessageStream<>(MessageStream.empty().cast(), MessageStream.empty().cast());
        } else if (messages.size() == 1) {
            return new ConcatenatingMessageStream<>(MessageStream.just(messages.getFirst()),
                                                    MessageStream.empty().cast());
        }
        return new ConcatenatingMessageStream<>(
                MessageStream.just(messages.getFirst()),
                MessageStream.fromIterable(messages.subList(1, messages.size()))
        );
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        Assumptions.abort("ConcatenatingMessageStream doesn't support explicit single-value streams");
        return null;
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        Assumptions.abort("ConcatenatingMessageStream doesn't support explicitly empty streams");
        return null;
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages,
                                                        RuntimeException failure) {
        return completedTestSubject(messages).concatWith(MessageStream.failed(failure));
    }

    @Override
    protected Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"),
                                  "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Nested
    class ConcatenatingOnAvailableFromFutureTest {

        private final AtomicBoolean callbackCalled = new AtomicBoolean();

        private CompletableFuture<Message> future1;
        private CompletableFuture<Message> future2;

        @BeforeEach
        public void reset() {
            future1 = new CompletableFuture<>();
            future2 = new CompletableFuture<>();
            MessageStream<Message> stream1 = MessageStream.fromFuture(future1);
            MessageStream<Message> stream2 = MessageStream.fromFuture(future2);
            MessageStream<Message> testSubject = stream1.concatWith(stream2);
            testSubject.setCallback(() -> callbackCalled.set(true));
            assertFalse(callbackCalled.getAndSet(false));
        }

        @Test
        void shouldNotDoCallbackIfFirstIsNotCompleted() {
            future2.complete(createRandomMessage());
            assertFalse(callbackCalled.getAndSet(false));
        }

        @Test
        void shouldDoCallbackIfFirstIsCompletedWithMessage() {
            future1.complete(createRandomMessage());
            assertTrue(callbackCalled.getAndSet(false));
        }

        /*
         * TODO #4424
         *
         * If ConcatenatingMessageStream is fixed not to trigger this callback,
         * DistributedQueryBusSubscriptionQueryTest (which has some other flakey tests)
         * starts failing relatively often with a hang.
         *
         * ConcatenatingMessageStream should probably be able to always call
         * signalProgress, and AbstractMessageStream should not only check awaitingData,
         * but also hasNextAvailable() || isCompleted() before triggering the callback.
         *
         * However, that breaks a different test in DistributedQueryBusSubscriptionQueryTest.
         * Needs further investigation.
         */

        @Test
        @Disabled("Weird interaction with DistributedQueryBusSubscriptionQueryTest")
        void shouldNotDoCallbackIfFirstIsCompletedWithoutMessages() {
            future1.complete(null);
            assertFalse(callbackCalled.getAndSet(false));
        }

        @Test
        void shouldDoCallbackIfFirstIsCompletedWithException() {
            future1.completeExceptionally(new IllegalArgumentException("Error"));
            assertTrue(callbackCalled.getAndSet(false));
        }

        @Test
        void shouldDoCallbackAfterFirstStreamCompletedWithoutMessages() {
            future1 = CompletableFuture.completedFuture(null);
            future2 = new CompletableFuture<>();
            MessageStream<Message> stream1 = MessageStream.fromFuture(future1);
            MessageStream<Message> stream2 = MessageStream.fromFuture(future2);
            MessageStream<Message> testSubject = stream1.concatWith(stream2);
            testSubject.setCallback(() -> callbackCalled.set(true));

            assertFalse(callbackCalled.getAndSet(false));

            assertTrue(stream1.isCompleted());
            assertFalse(stream1.hasNextAvailable());

            assertFalse(callbackCalled.getAndSet(false));

            // Only after stream 2 has messages should the callback be called
            future2.complete(createRandomMessage());

            assertTrue(callbackCalled.getAndSet(false));
        }
    }

    @Nested
    class ConcatenatingOnAvailableOnStreamsTest {

        private final QueueMessageStream<Message> stream1 = new QueueMessageStream<>();
        private final QueueMessageStream<Message> stream2 = Mockito.spy(new QueueMessageStream<>());
        private final MessageStream<Message> testSubject = stream1.concatWith(stream2.cast());

        private final AtomicBoolean callbackCalled = new AtomicBoolean();

        @BeforeEach
        void reset() {
            testSubject.setCallback(() -> callbackCalled.set(true));

            // ensure there was no immediate call:
            assertThat(callbackCalled.getAndSet(false)).isFalse();
        }

        @Test
        void shouldDoCallbackIfFirstIsCompletedAfterSecond() {
            stream2.seal();
            assertFalse(callbackCalled.getAndSet(false));
            stream1.seal();
            assertTrue(callbackCalled.getAndSet(false));
        }

        @Test
        void shouldCloseSecondIfFirstCompletesWithError() {
            stream1.sealExceptionally(new IllegalArgumentException("Error"));

            assertTrue(callbackCalled.getAndSet(false));
            assertFalse(testSubject.error().isPresent());  // error from producer side is only reported once stream is consumed up to that point
            assertThat(testSubject.next()).isEmpty();  // call next to surface error
            assertTrue(testSubject.error().isPresent());  // next called now, error should surface
            verify(stream2).close();
        }

        @Test
        void shouldDoCallbackWhenFirstStreamHasMessageAppended() {
            stream1.offer(createRandomMessage(), Context.empty());

            assertThat(callbackCalled.getAndSet(false)).isTrue();
        }

        @Test
        void shouldNotDoCallbackWhenSecondMessageArrivedBeforeItWasAccessed() {
            var future1 = new CompletableFuture<Message>();
            var future2 = new CompletableFuture<Message>();
            var stream = MessageStream.fromFuture(future1).concatWith(MessageStream.fromFuture(future2));

            stream.setCallback(() -> callbackCalled.set(true));

            future1.complete(createRandomMessage());

            assertTrue(callbackCalled.getAndSet(false));
            assertTrue(stream.next().isPresent());

            /*
             * At this point, the next message hasn't been accessed via next(), peek(), or
             * hasNextAvailable(), so no new callback is expected, even if the stream internally
             * already knows there is no next element. Once the user queries the stream and
             * it reaches a point where it can report no further messages available, a new
             * callback should be triggered when a message arrives.
             */

            future2.complete(createRandomMessage());

            // callback NOT expected as no attempt was made to access next message before future2 completed
            assertFalse(callbackCalled.get());
            assertTrue(stream.next().isPresent());  // not reached end, so not yet complete
            assertFalse(callbackCalled.get());
            assertFalse(stream.next().isPresent());  // reached end, so should be complete now
            assertTrue(callbackCalled.get());
        }

        @Test
        void shouldDoCallbackWhenFirstStreamHasMessageAndSecondStreamHasMessage() {
            var future1 = new CompletableFuture<Message>();
            var future2 = new CompletableFuture<Message>();
            var stream = MessageStream.fromFuture(future1).concatWith(MessageStream.fromFuture(future2));

            stream.setCallback(() -> callbackCalled.set(true));

            future1.complete(createRandomMessage());

            assertTrue(callbackCalled.getAndSet(false));
            assertTrue(stream.next().isPresent());

            /*
             * At this point, the next message hasn't been accessed via next(), peek(), or
             * hasNextAvailable(), so no new callback is expected, even if the stream internally
             * already knows there is no next element. Once the user queries the stream and
             * it reaches a point where it can report no further messages available, a new
             * callback should be triggered when a message arrives.
             */

            assertFalse(stream.hasNextAvailable());  // attempt at accessing next message

            future2.complete(createRandomMessage());

            assertTrue(callbackCalled.getAndSet(false));  // callback expected!
            assertTrue(stream.next().isPresent());
        }

        @Test
        void shouldDoCallbackWhenFirstStreamCompletesExceptionally() {
            var future = new CompletableFuture<Message>();
            var stream = MessageStream.fromFuture(future).concatWith(MessageStream.empty());
            stream.setCallback(() -> callbackCalled.set(true));

            future.completeExceptionally(new IllegalArgumentException("Error"));

            assertTrue(callbackCalled.getAndSet(false));
        }
    }

    @Test
    void transitionToSecondaryStreamShouldBeImmediate() {
        Message msg1 = createRandomMessage();
        Message msg2 = createRandomMessage();

        ConcatenatingMessageStream<Message> ms = new ConcatenatingMessageStream<>(
                MessageStream.fromIterable(List.of(msg1)),
                MessageStream.fromIterable(List.of(msg2))
        );

        assertThat(ms.next()).map(Entry::message).contains(msg1);
        assertThat(ms.next()).map(Entry::message).contains(msg2);
        assertThat(ms.next()).isEmpty();
    }

    @Test
    void peekShouldReturnSecondMessageIfFirstStreamWasConsumedFully() {
        Message msg1 = createRandomMessage();
        Message msg2 = createRandomMessage();
        MessageStream<Message> stream = MessageStream.just(msg1).concatWith(MessageStream.just(msg2));

        assertThat(stream.next()).map(Entry::message).contains(msg1);
        assertThat(stream.peek()).map(Entry::message).contains(msg2);
    }

    @Test
    void shouldDoCompletionCallbackOnNextInteractionWithFullyConsumedStream() {
        List<Message> originalMessages = List.of(createRandomMessage(), createRandomMessage(), createRandomMessage());
        AtomicBoolean callbackExecuted = new AtomicBoolean(false);

        MessageStream<Message> original = new ConcatenatingMessageStream<>(
            MessageStream.just(originalMessages.get(0)),
            MessageStream.fromIterable(originalMessages.subList(1, originalMessages.size()))
        );
        MessageStream<Message> withCallback = original.onComplete(() -> callbackExecuted.set(true));

        assertFalse(callbackExecuted.get());
        assertThat(withCallback.next()).map(Entry::message).contains(originalMessages.get(0));
        assertFalse(callbackExecuted.get());
        assertThat(withCallback.next()).map(Entry::message).contains(originalMessages.get(1));
        assertFalse(callbackExecuted.get());
        assertThat(withCallback.next()).map(Entry::message).contains(originalMessages.get(2));
        assertFalse(callbackExecuted.get());
        assertThat(withCallback.next()).isEmpty();

        assertTrue(callbackExecuted.get());
    }
}