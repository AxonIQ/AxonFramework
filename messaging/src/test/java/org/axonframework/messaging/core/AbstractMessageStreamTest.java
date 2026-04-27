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

import org.assertj.core.api.InstanceOfAssertFactories;
import org.axonframework.messaging.core.AbstractMessageStream.FetchResult;
import org.axonframework.messaging.core.MessageStream.Entry;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AbstractMessageStream} focusing on the internal state machine: callback mechanics,
 * signalProgress/awaitingData interaction, initialization, peek caching, and the onCompleted hook.
 *
 * @author John Hendrikx
 */
class AbstractMessageStreamTest {

    /**
     * Minimal {@link AbstractMessageStream} that lets tests control exactly what {@link FetchResult} values are
     * returned and lets them call {@link #signalProgress()} from outside.
     */
    private static class ControllableStream extends AbstractMessageStream<Message> {

        private final Queue<FetchResult<Entry<Message>>> results = new ArrayDeque<>();

        private RuntimeException thrownExceptionInOnCompleted;
        private int onCompletedCount;

        void enqueue(FetchResult<Entry<Message>> result) {
            results.add(result);
        }

        void triggerSignalProgress() {
            signalProgress();
        }

        void callInitialize(FetchResult<Entry<Message>> initialResult) {
            initialize(initialResult);
        }

        void throwExceptionInOnCompleted(RuntimeException e) {
            thrownExceptionInOnCompleted = e;
        }

        @NonNull
        @Override
        protected FetchResult<Entry<Message>> fetchNext() {
            FetchResult<Entry<Message>> result = results.poll();

            return result != null ? result : FetchResult.notReady();
        }

        @Override
        protected void onCompleted() {
            onCompletedCount++;

            if (thrownExceptionInOnCompleted != null) {
                throw thrownExceptionInOnCompleted;
            }
        }

        int onCompletedCount() {
            return onCompletedCount;
        }
    }

    private static Entry<Message> entryOf(String id) {
        return new SimpleEntry<>(new GenericMessage(new MessageType(id), id));
    }

    @Nested
    class WhenInitialized {

        ControllableStream stream = new ControllableStream();

        @Test
        void withNotReadyThenSetsAwaitingDataAndDoesNotComplete() {
            stream.callInitialize(FetchResult.notReady());

            assertThat(stream.isCompleted()).isFalse();
            assertThat(stream.hasNextAvailable()).isFalse();
        }

        @Test
        void withCompletedThenImmediatelyCompletesStream() {
            stream.callInitialize(FetchResult.completed());

            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).isEmpty();
        }

        @Test
        void withErrorThenCompletesStreamExceptionally() {
            RuntimeException failure = new RuntimeException("init error");

            stream.callInitialize(FetchResult.error(failure));

            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).contains(failure);
        }

        @Test
        void withValueThenThrowsIllegalArgumentException() {
            assertThatThrownBy(() -> stream.callInitialize(FetchResult.of(entryOf("msg"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void calledTwiceThenThrowsIllegalStateException() {
            stream.callInitialize(FetchResult.notReady());

            assertThatThrownBy(() -> stream.callInitialize(FetchResult.notReady()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void afterAnyInteractionThenThrowsIllegalStateException() {
            stream.next();

            assertThatThrownBy(() -> stream.callInitialize(FetchResult.notReady()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class WhenNextCalled {

        ControllableStream stream = new ControllableStream();

        @Test
        void whenValueAvailableThenReturnsValue() {
            Entry<Message> expected = entryOf("msg1");

            stream.enqueue(FetchResult.of(expected));

            assertThat(stream.next()).contains(expected);
        }

        @Test
        void whenNullValueAvailableThenReturnsEmptyOptional() {
            stream.enqueue(FetchResult.of((Entry<Message>) null));

            assertThat(stream.next()).isEmpty();
        }

        @Test
        void whenNotReadyThenReturnsEmptyAndDoesNotComplete() {
            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isFalse();
        }

        @Test
        void whenCompletedResultThenCompletesStreamAndReturnsEmpty() {
            stream.enqueue(FetchResult.completed());

            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).isEmpty();
        }

        @Test
        void whenErrorResultThenCompletesExceptionallyAndReturnsEmpty() {
            RuntimeException failure = new RuntimeException("stream error");

            stream.enqueue(FetchResult.error(failure));

            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).contains(failure);
        }

        @Test
        void whenAlreadyCompletedThenReturnsEmpty() {
            stream.close();

            assertThat(stream.next()).isEmpty();
        }

        @Test
        void consumesCachedPeekedEntry() {
            Entry<Message> entry = entryOf("peeked");

            stream.enqueue(FetchResult.of(entry));
            stream.peek(); // caches entry - queue is now empty

            // next() must return the cached peek result, not call fetchNext() again
            assertThat(stream.next()).contains(entry);
            assertThat(stream.next()).isEmpty(); // no more entries
        }

        @Test
        void clearsPeekedEntryAfterConsumption() {
            Entry<Message> entry = entryOf("entry");

            stream.enqueue(FetchResult.of(entry));
            stream.peek();
            stream.next(); // consume cached entry

            // second next() - queue empty -> NotReady
            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isFalse();
        }
    }

    @Nested
    class WhenPeekCalled {

        @Test
        void returnsEntryWithoutConsuming() {
            ControllableStream stream = new ControllableStream();
            Entry<Message> entry = entryOf("msg");

            stream.enqueue(FetchResult.of(entry));

            assertThat(stream.peek()).contains(entry);
            assertThat(stream.next()).contains(entry);
        }

        @Test
        void cachesResultAndFetchNextCalledOnlyOnce() {
            // given: only one result enqueued; a second fetchNext call would return NotReady
            ControllableStream stream = new ControllableStream();
            Entry<Message> entry = entryOf("msg");

            stream.enqueue(FetchResult.of(entry));

            assertThat(stream.peek()).contains(entry);
            assertThat(stream.peek()).contains(entry);
        }

        @Test
        void returnsEmptyWhenCompleted() {
            ControllableStream stream = new ControllableStream();

            stream.close();

            assertThat(stream.peek()).isEmpty();
            assertThat(stream.hasNextAvailable()).isFalse();
        }

        @Test
        void returnsEmptyWhenAwaitingData() {
            // given: empty queue -> fetchNext returns NotReady
            ControllableStream stream = new ControllableStream();

            assertThat(stream.peek()).isEmpty();
            assertThat(stream.hasNextAvailable()).isFalse();
            assertThat(stream.isCompleted()).isFalse();
        }
    }

    @Nested
    class WhenSetCallbackCalled {

        AtomicInteger count = new AtomicInteger();
        ControllableStream stream = new ControllableStream();

        @Test
        void whenDataAvailableThenFiresImmediately() {
            stream.enqueue(FetchResult.of(entryOf("msg")));
            stream.setCallback(count::incrementAndGet);

            assertThat(count.get()).isEqualTo(1);
        }

        @Test
        void whenAlreadyCompletedThenFiresExactlyOnce() {
            stream.close();
            stream.setCallback(count::incrementAndGet);

            assertThat(count.get()).isEqualTo(1);
        }

        @Test
        void whenStreamCompletesViaFetchNextDuringProbeThenFiresExactlyOnce() {

            /*
             * Surfaces the double-callback bug: inside setCallback, hasNextAvailable() probes
             * the stream via peek() -> next() -> fetchNext(). If fetchNext() returns Completed,
             * complete() fires the callback (count=1). Without the wasCompleted guard, the
             * outer invokeCallbackSafely() at the end of setCallback would fire again (count=2).
             */

            stream.enqueue(FetchResult.completed());
            stream.setCallback(count::incrementAndGet);

            assertThat(count.get()).isEqualTo(1);
            assertThat(stream.isCompleted()).isTrue();
        }

        @Test
        void whenStreamFailsViaFetchNextDuringProbeThenFiresExactlyOnce() {
            stream.enqueue(FetchResult.error(new RuntimeException("probe error")));
            stream.setCallback(count::incrementAndGet);

            assertThat(count.get()).isEqualTo(1);
            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).isPresent();
        }

        @Test
        void whenAwaitingDataThenDoesNotFireCallback() {
            // next() returned empty (NotReady) -> awaitingData=true
            stream.next();
            stream.setCallback(count::incrementAndGet);

            assertThat(count.get()).isEqualTo(0);
        }

        @SuppressWarnings("DataFlowIssue")
        @Test
        void withNullCallbackThenThrowsNullPointerException() {
            assertThatThrownBy(() -> stream.setCallback(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class WhenSignalProgressCalled {

        ControllableStream stream = new ControllableStream();
        AtomicInteger count = new AtomicInteger();

        @Test
        void whenAwaitingDataThenFiresCallback() {
            stream.next(); // awaitingData=true
            stream.setCallback(count::incrementAndGet);

            assertThat(count.get()).isEqualTo(0); // sanity: not yet fired

            stream.triggerSignalProgress();

            assertThat(count.get()).isEqualTo(1);
        }

        @Test
        void whenNotAwaitingDataThenDoesNotFireCallback() {
            stream.enqueue(FetchResult.of(entryOf("msg")));
            stream.setCallback(count::incrementAndGet); // fires once (data available)

            count.set(0);

            stream.triggerSignalProgress();

            assertThat(count.get()).isEqualTo(0);
        }

        @Test
        void whenCompletedThenDoesNotFireCallback() {
            stream.close();
            stream.setCallback(count::incrementAndGet); // fires once (already completed)

            count.set(0);

            stream.triggerSignalProgress();

            assertThat(count.get()).isEqualTo(0);
        }

        @Test
        void signalBeforeConsumerAwaitsDoesNotLoseMessage() {

            /*
             * Verifies the ordering contract: data is placed before signalProgress() is called.
             * The signal arrives while awaitingData=false (ignored), but because the data was
             * placed first, the consumer finds it via fetchNext() on the next next() call.
             */

            Entry<Message> entry = entryOf("ordered-msg");

            stream.enqueue(FetchResult.of(entry));
            stream.triggerSignalProgress(); // awaitingData=false → ignored

            assertThat(stream.next()).contains(entry);
        }

        @Test
        void fromProducerThreadThenFiresCallbackForAwaitingConsumer() throws InterruptedException {
            stream.next(); // awaitingData=true

            CountDownLatch callbackFired = new CountDownLatch(1);

            stream.setCallback(() -> {
                count.incrementAndGet();
                callbackFired.countDown();
            });

            assertThat(count.get()).isEqualTo(0); // not fired yet

            Thread producer = Thread.ofVirtual().start(() -> {
                stream.enqueue(FetchResult.of(entryOf("async-msg")));
                stream.triggerSignalProgress();
            });

            assertThat(callbackFired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(count.get()).isEqualTo(1);
            assertThat(stream.next()).isPresent();

            producer.join(5000);
        }
    }

    @Nested
    class WhenCloseCalled {

        ControllableStream stream = new ControllableStream();

        @Test
        void completesStreamNormally() {
            stream.close();

            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).isEmpty();
        }

        @Test
        void clearsPeekedEntry() {
            stream.enqueue(FetchResult.of(entryOf("msg")));

            assertThat(stream.peek()).isPresent();

            stream.close();

            assertThat(stream.peek()).isEmpty();
            assertThat(stream.next()).isEmpty();
        }

        @Test
        void isIdempotent() {
            stream.close();
            stream.close();
            stream.close();

            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.onCompletedCount()).isEqualTo(1);
        }

        @Test
        void firesCallbackOnce() {
            AtomicInteger count = new AtomicInteger();

            stream.setCallback(count::incrementAndGet); // returns early: awaiting data after probe

            assertThat(count.get()).isEqualTo(0);

            stream.close();

            assertThat(count.get()).isEqualTo(1);
        }
    }

    @Nested
    class WhenStreamCompletes {

        ControllableStream stream = new ControllableStream();

        @Test
        void onCompletedCalledWhenCompletedNormally() {
            stream.enqueue(FetchResult.completed());
            stream.next();

            assertThat(stream.onCompletedCount()).isEqualTo(1);
        }

        @Test
        void onCompletedCalledWhenCompletedExceptionally() {
            stream.enqueue(FetchResult.error(new RuntimeException("failure")));
            stream.next();

            assertThat(stream.onCompletedCount()).isEqualTo(1);
        }

        @Nested
        class AndOnCompletedThrowsException {

            RuntimeException onCompletedException = new IllegalArgumentException("boo");

            {
                stream.throwExceptionInOnCompleted(onCompletedException);
            }

            @Test
            void onCompletedCalledWhenCompletedNormally() {
                stream.enqueue(FetchResult.completed());
                stream.next();

                assertThat(stream.onCompletedCount()).isEqualTo(1);
                assertThat(stream.error()).contains(onCompletedException);
            }

            @Test
            void onCompletedCalledWhenCompletedExceptionally() {
                stream.enqueue(FetchResult.error(new IllegalStateException("failure")));
                stream.next();

                assertThat(stream.onCompletedCount()).isEqualTo(1);
                assertThat(stream.error()).get(InstanceOfAssertFactories.THROWABLE)
                                          .isInstanceOf(IllegalStateException.class)
                                          .hasMessage("failure")
                                          .hasSuppressedException(onCompletedException);
            }
        }

        @Test
        void onCompletedCalledExactlyOnceWhenClosedMultipleTimes() {
            stream.close();
            stream.close();

            assertThat(stream.onCompletedCount()).isEqualTo(1);
        }
    }

    @Nested
    class WhenCallbackThrows {

        ControllableStream stream = new ControllableStream();

        @Test
        void completesStreamExceptionally() {
            stream.next(); // awaitingData=true

            RuntimeException callbackFailure = new RuntimeException("callback boom");

            stream.setCallback(() -> {
                throw callbackFailure;
            });

            stream.enqueue(FetchResult.of(entryOf("msg")));
            stream.triggerSignalProgress(); // fires callback → catches exception

            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).contains(callbackFailure);
        }

        @Test
        void duringCompletionSignalThenNormalCompletionIsPreserved() {

            /*
             * The class doc says: "If the registered callback throws an exception, the stream is
             * completed exceptionally, unless the callback was called to signal completion."
             *
             * When complete() fires the callback, and it throws, completeExceptionally() is called
             * but is a no-op because completed=true already. The stream remains normally completed.
             */

            stream.enqueue(FetchResult.completed());
            stream.setCallback(() -> {
                throw new RuntimeException("callback during completion");
            });

            // setCallback probes hasNextAvailable() -> triggers completion -> callback fires and throws
            // completeExceptionally is a no-op since completed=true already

            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).isEmpty();
        }
    }
}
