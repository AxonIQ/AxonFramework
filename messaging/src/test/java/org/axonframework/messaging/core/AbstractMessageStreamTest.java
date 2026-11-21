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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.test.appender.ListAppender;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.axonframework.messaging.core.AbstractMessageStream.FetchResult;
import org.axonframework.messaging.core.MessageStream.Entry;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

            @Test
            void closeLeavesErrorEmpty() {
                stream.close();

                assertThat(stream.onCompletedCount()).isEqualTo(1);
                assertThat(stream.error()).isEmpty(); // a closed stream's state stays as it was when closed
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

    @Nested
    class WhenBridgingFromDelegate {

        /*
         * Tests for FetchResult.of(MessageStream<M> delegate) -- the static bridge that maps a
         * delegate stream's current state to the FetchResult model.
         */

        @Test
        void withValueAvailableThenReturnsValueResult() {
            // given
            ControllableStream delegate = new ControllableStream();
            Entry<Message> entry = entryOf("msg");
            delegate.enqueue(FetchResult.of(entry));

            // when
            FetchResult<Entry<Message>> result = FetchResult.of(delegate);

            // then
            assertThat(result).isEqualTo(new FetchResult.Value<>(entry));
        }

        @Test
        void withNoValueAndNotCompletedThenReturnsNotReady() {
            // given: nothing enqueued, not closed -- next() returns empty while isCompleted()=false
            ControllableStream delegate = new ControllableStream();

            // when
            FetchResult<Entry<Message>> result = FetchResult.of(delegate);

            // then: must not throw, and must signal the stream is temporarily unavailable
            assertThat(result).isEqualTo(FetchResult.notReady());
        }

        @Test
        void withCompletedDelegateThenReturnsCompleted() {
            // given
            ControllableStream delegate = new ControllableStream();
            delegate.close();

            // when
            FetchResult<Entry<Message>> result = FetchResult.of(delegate);

            // then
            assertThat(result).isEqualTo(FetchResult.completed());
        }

        @Test
        void withFailedDelegateThenReturnsError() {
            // given: enqueue an error result; next() will process it, completing the stream exceptionally
            ControllableStream delegate = new ControllableStream();
            RuntimeException failure = new RuntimeException("stream failed");
            delegate.enqueue(FetchResult.error(failure));

            // when: bridge calls next() which processes the error; bridge then reads isCompleted()+error()
            FetchResult<Entry<Message>> result = FetchResult.of(delegate);

            // then
            assertThat(result).isInstanceOf(FetchResult.Error.class);
            assertThat(((FetchResult.Error<?>) result).error()).isSameAs(failure);
        }
    }

    @Nested
    class WhenCallbackReadsFromTheStreamWhileHandlingASignal {

        ControllableStream stream = new ControllableStream();

        @Test
        void hasNextAvailableIsRejected() {
            assertThat(readFromSignalCallback(() -> stream.hasNextAvailable())).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void peekIsRejected() {
            assertThat(readFromSignalCallback(() -> stream.peek())).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void nextIsRejected() {
            assertThat(readFromSignalCallback(() -> stream.next())).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void completesStreamExceptionallyWhenTheCallbackDoesNotHandleTheRejection() {
            // given
            stream.next(); // awaitingData=true
            stream.setCallback(stream::next);
            stream.enqueue(FetchResult.of(entryOf("msg")));

            // when
            stream.triggerSignalProgress();

            // then
            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).get().isInstanceOf(IllegalStateException.class);
        }

        @Test
        void readingIsAllowedAfterTheCallbackReturned() {
            // given
            stream.next(); // awaitingData=true
            stream.setCallback(() -> { });
            stream.enqueue(FetchResult.of(entryOf("msg")));

            // when
            stream.triggerSignalProgress();

            // then
            assertThat(stream.next()).isPresent();
            assertThat(stream.error()).isEmpty();
        }

        @Test
        void readingIsAllowedFromAnotherThreadWhileTheCallbackRuns() throws InterruptedException {
            // given
            CountDownLatch inCallback = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            stream.next(); // awaitingData=true
            stream.setCallback(() -> {
                inCallback.countDown();
                await().atMost(Duration.ofSeconds(5)).until(() -> release.getCount() == 0);
            });
            stream.enqueue(FetchResult.of(entryOf("msg")));

            Thread signaler = new Thread(stream::triggerSignalProgress);

            // when
            signaler.start();
            assertThat(inCallback.await(5, TimeUnit.SECONDS)).isTrue();

            // then
            assertThat(stream.next()).isPresent();

            release.countDown();
            signaler.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(stream.error()).isEmpty();
        }

        @Test
        void readingIsAllowedInACallbackThatIsInvokedWhileRegistering() {
            // given
            stream.enqueue(FetchResult.of(entryOf("msg")));
            AtomicReference<Optional<Entry<Message>>> read = new AtomicReference<>();

            // when
            stream.setCallback(() -> read.set(stream.next()));

            // then
            assertThat(read.get()).isPresent();
            assertThat(stream.error()).isEmpty();
        }

        @Test
        void readingADifferentStreamIsRejected() {
            // given
            ControllableStream other = new ControllableStream();
            other.enqueue(FetchResult.of(entryOf("other")));
            AtomicReference<Throwable> failure = new AtomicReference<>();

            stream.next(); // awaitingData=true
            stream.setCallback(() -> {
                try {
                    other.next();
                }
                catch (IllegalStateException e) {
                    failure.set(e);
                }
            });
            stream.enqueue(FetchResult.of(entryOf("msg")));

            // when
            stream.triggerSignalProgress();

            // then the thread is shared with every stream, so reading any of them there is rejected
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            assertThat(other.next()).isPresent();
        }

        @Test
        void rejectionNamesTheReadTheStreamsAndTheCallbackThatAreInvolved() {
            // given
            ControllableStream other = new ControllableStream();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable callback = new Runnable() {
                @Override
                public void run() {
                    try {
                        other.peek();
                    }
                    catch (IllegalStateException e) {
                        failure.set(e);
                    }
                }

                @Override
                public String toString() {
                    return "the-offending-callback";
                }
            };

            stream.next(); // awaitingData=true
            stream.setCallback(callback);
            stream.enqueue(FetchResult.of(entryOf("msg")));

            // when
            stream.triggerSignalProgress();

            // then
            assertThat(failure.get()).hasMessageContaining("peek()")
                                     .hasMessageContaining(other.toString())
                                     .hasMessageContaining("the-offending-callback")
                                     .hasMessageContaining(Thread.currentThread().getName());
        }

        @Test
        void readingIsRejectedAgainAfterANestedSignalReturnedToTheEnclosingCallback() {
            // given
            ControllableStream inner = new ControllableStream();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            inner.next(); // awaitingData=true
            inner.setCallback(() -> { });
            inner.enqueue(FetchResult.of(entryOf("inner")));
            stream.next(); // awaitingData=true
            stream.setCallback(() -> {
                inner.triggerSignalProgress();

                try {
                    stream.hasNextAvailable();
                }
                catch (IllegalStateException e) {
                    failure.set(e);
                }
            });
            stream.enqueue(FetchResult.of(entryOf("msg")));

            // when
            stream.triggerSignalProgress();

            // then reading stays rejected for as long as the enclosing callback runs, and is allowed again after it
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            assertThat(stream.hasNextAvailable()).isTrue();
        }

        @Test
        void readingIsAllowedAfterACallbackThatThrew() {
            // given
            ControllableStream other = new ControllableStream();
            other.enqueue(FetchResult.of(entryOf("other")));

            stream.next(); // awaitingData=true
            stream.setCallback(() -> {
                throw new RuntimeException("callback boom");
            });
            stream.enqueue(FetchResult.of(entryOf("msg")));

            // when
            stream.triggerSignalProgress();

            // then the thread is not left marked as handling a signal
            assertThat(other.next()).isPresent();
        }

        @Test
        void readingIsRejectedUntilTheOutermostSignalReturned() {
            // given a callback that signals another stream, which returns before the callback reads
            ControllableStream other = new ControllableStream();
            other.next(); // awaitingData=true
            other.setCallback(() -> { });
            other.enqueue(FetchResult.of(entryOf("other")));
            AtomicReference<Throwable> failure = new AtomicReference<>();

            stream.next(); // awaitingData=true
            stream.setCallback(() -> {
                other.triggerSignalProgress();

                try {
                    stream.next();
                }
                catch (IllegalStateException e) {
                    failure.set(e);
                }
            });
            stream.enqueue(FetchResult.of(entryOf("msg")));

            // when
            stream.triggerSignalProgress();

            // then the nested signal did not end the first one's restriction, but the first one's end did
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            assertThat(stream.next()).isPresent();
        }

        /**
         * Signals the stream, with a callback that performs the given read, and returns the failure of that read.
         */
        private Throwable readFromSignalCallback(Runnable read) {
            AtomicReference<Throwable> failure = new AtomicReference<>();

            stream.next(); // awaitingData=true
            stream.setCallback(() -> {
                try {
                    read.run();
                }
                catch (IllegalStateException e) {
                    failure.set(e);
                }
            });
            stream.enqueue(FetchResult.of(entryOf("msg")));
            stream.triggerSignalProgress();

            return failure.get();
        }
    }

    @Nested
    class WhenACallbackTakesLongToHandleASignal {

        private ListAppender appender;
        private Logger streamLogger;
        private boolean previousAdditive;
        private Level previousLevel;

        @BeforeEach
        void attachAppender() {
            appender = new ListAppender("SlowCallbackTestAppender");
            appender.start();

            streamLogger = (Logger) LogManager.getLogger(AbstractMessageStream.class);
            previousAdditive = streamLogger.isAdditive();
            previousLevel = streamLogger.getLevel();
            streamLogger.setAdditive(false);
            streamLogger.setLevel(Level.DEBUG);
            streamLogger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            streamLogger.removeAppender(appender);
            streamLogger.setAdditive(previousAdditive);
            streamLogger.setLevel(previousLevel);
            appender.stop();
        }

        @Test
        void logsTheCallbackAndTheStreamAtDebugLevel() {
            // given
            ControllableStream stream = new ControllableStream();

            stream.next(); // awaitingData=true
            stream.setCallback(() -> {
                try {
                    Thread.sleep(150);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            stream.enqueue(FetchResult.of(entryOf("msg")));

            // when
            stream.triggerSignalProgress();

            // then
            assertThat(appender.getEvents()).hasSize(1);

            LogEvent logged = appender.getEvents().getFirst();

            assertThat(logged.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(logged.getMessage().getFormattedMessage())
                    .contains("ran for")
                    .contains(ControllableStream.class.getName())
                    .contains(WhenACallbackTakesLongToHandleASignal.class.getName());
        }

        @Test
        void logsNothingForACallbackThatIsFast() {
            // given
            ControllableStream stream = new ControllableStream();

            stream.next(); // awaitingData=true
            stream.setCallback(() -> { });
            stream.enqueue(FetchResult.of(entryOf("msg")));

            // when
            stream.triggerSignalProgress();

            // then
            assertThat(appender.getEvents()).isEmpty();
        }
    }

    @Nested
    class WhenCompletionTriggersCallback {

        /*
         * The concurrency model of this class states that producer-side signals, and the callbacks they
         * trigger, flow upstream (inner to outer), and that a callback must therefore never be invoked
         * while this stream's lock is held. signalProgress() honors that; completion must do the same.
         *
         * A composed stream registers a callback that reads the stream it wraps, so a callback running
         * while the completing stream is locked takes a second lock in the opposite direction. Two
         * streams completing at once then deadlock, leaving an event processor with no events, no error,
         * and no thread of its own to blame.
         */

        @Test
        void thenCallbackDoesNotRunWhileStreamIsLocked() {
            // given: a callback that lets another thread interact with the very stream that is completing
            ControllableStream stream = new ControllableStream();
            AtomicBoolean otherThreadCouldReadStream = new AtomicBoolean();

            CountDownLatch probeReadStream = new CountDownLatch(1);

            stream.setCallback(() -> {
                Thread probe = new Thread(() -> {
                    stream.isCompleted();

                    probeReadStream.countDown();
                });

                probe.setDaemon(true);
                probe.start();

                try {
                    // recorded inside the callback: whether the other thread got through while it runs
                    otherThreadCouldReadStream.set(probeReadStream.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            stream.enqueue(FetchResult.completed());

            // when: the stream completes, firing the callback
            stream.next();

            // then
            assertThat(otherThreadCouldReadStream).isTrue();
        }

        @Test
        void thenTwoStreamsCompletingConcurrentlyDoNotDeadlock() throws Exception {
            // given: two streams whose callbacks read each other, as composed streams do
            ControllableStream first = new ControllableStream();
            ControllableStream second = new ControllableStream();
            CyclicBarrier bothInsideCallback = new CyclicBarrier(2);

            first.setCallback(() -> {
                awaitQuietly(bothInsideCallback);

                second.isCompleted();
            });
            second.setCallback(() -> {
                awaitQuietly(bothInsideCallback);

                first.isCompleted();
            });

            first.enqueue(FetchResult.completed());
            second.enqueue(FetchResult.completed());

            CountDownLatch bothReturned = new CountDownLatch(2);

            // when: both complete at the same moment, each callback reaching into the other stream
            startDaemon(() -> {
                first.next();

                bothReturned.countDown();
            });
            startDaemon(() -> {
                second.next();

                bothReturned.countDown();
            });

            // then: neither thread holds a lock the other one needs
            assertThat(bothReturned.await(5, TimeUnit.SECONDS)).isTrue();
        }

        @Test
        void thenCompletionIsVisibleToTheCallback() {
            // given: the completion the callback announces must be observable by that callback
            ControllableStream stream = new ControllableStream();
            AtomicBoolean completedDuringCallback = new AtomicBoolean();

            stream.setCallback(() -> completedDuringCallback.set(stream.isCompleted()));
            stream.enqueue(FetchResult.completed());

            // when
            stream.next();

            // then
            assertThat(completedDuringCallback).isTrue();
        }

        private void awaitQuietly(CyclicBarrier barrier) {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to synchronize both callbacks.", e);
            }
        }

        private void startDaemon(Runnable task) {
            Thread thread = new Thread(task);

            thread.setDaemon(true);
            thread.start();
        }
    }

    /*
     * fetchNext() may block, for example while it waits for a database connection. These tests hold
     * back a fetchNext() call and check how the rest of the stream behaves meanwhile: other calls do
     * not wait for it, only one fetch runs at a time, signals are not lost, and close() takes effect
     * immediately, with onCompleted() running once the fetch in progress has returned.
     */
    @Nested
    class WhileFetchNextIsInProgress {

        private final GatedStream stream = new GatedStream();
        private final AtomicInteger callbackCount = new AtomicInteger();

        @AfterEach
        void releaseHeldBackFetches() {
            stream.releaseAllGates(); // never leave threads blocked when an assertion fails
        }

        @Nested
        class OtherCallsDoNotWait {

            @BeforeEach
            void holdBackAFetch() throws InterruptedException {
                Gate gate = stream.holdBackNextFetch(FetchResult::notReady);

                inBackground(stream::next);
                gate.awaitEntered();
            }

            @Test
            void signalProgressReturnsWithoutWaitingForFetchNext() throws InterruptedException {
                assertReturnsPromptly(stream::triggerSignalProgress);
            }

            @Test
            void isCompletedReturnsWithoutWaitingForFetchNext() throws InterruptedException {
                AtomicBoolean completed = new AtomicBoolean(true);

                assertReturnsPromptly(() -> completed.set(stream.isCompleted()));
                assertThat(completed).isFalse();
            }

            @Test
            void errorReturnsWithoutWaitingForFetchNext() throws InterruptedException {
                assertReturnsPromptly(stream::error);
            }

            @Test
            void nextReturnsEmptyWithoutWaitingOrFetchingAgain() throws InterruptedException {
                AtomicReference<Optional<Entry<Message>>> result = new AtomicReference<>();

                assertReturnsPromptly(() -> result.set(stream.next()));
                assertThat(result.get()).isEmpty();
                assertThat(stream.fetchNextCount()).isEqualTo(1);
            }

            @Test
            void hasNextAvailableReturnsFalseWithoutWaitingOrFetchingAgain() throws InterruptedException {
                AtomicBoolean available = new AtomicBoolean(true);

                assertReturnsPromptly(() -> available.set(stream.hasNextAvailable()));
                assertThat(available).isFalse();
                assertThat(stream.fetchNextCount()).isEqualTo(1);
            }
        }

        @Nested
        class OnlyOneFetchRuns {

            @Test
            void callsMadeDuringAFetchDoNotOverlapIt() throws InterruptedException {
                Gate gate = stream.holdBackNextFetch(FetchResult::notReady);

                inBackground(stream::next);
                gate.awaitEntered();

                List<Thread> callers = List.of(
                    inBackground(stream::next),
                    inBackground(stream::peek),
                    inBackground(stream::hasNextAvailable)
                );

                awaitReturnedOrBlocked(callers);
                gate.release();

                for (Thread caller : callers) {
                    assertThat(caller.join(Duration.ofSeconds(5))).isTrue();
                }

                assertThat(stream.overlappingFetches()).isFalse();
            }

            @Test
            void callerThatFoundNothingDuringAFetchIsCalledBackWhenTheFetchReturns() throws InterruptedException {
                stream.setCallback(callbackCount::incrementAndGet); // nothing is available yet, so no callback

                Gate gate = stream.holdBackNextFetch(() -> FetchResult.of(entryOf("fetched")));

                inBackground(stream::next);
                gate.awaitEntered();

                Thread caller = inBackground(stream::hasNextAvailable);

                awaitReturnedOrBlocked(List.of(caller));

                int callbacksBeforeRelease = callbackCount.get();

                gate.release();

                // the caller learned nothing during the fetch, so it must be told to look again
                await().until(() -> callbackCount.get() > callbacksBeforeRelease);
            }
        }

        @Nested
        class SignalsAreNotLost {

            @Test
            void callbackFiresWhenAFetchReturnsNotReadyAfterASignal() throws InterruptedException {
                Entry<Message> entry = entryOf("late");
                Gate gate = stream.holdBackNextFetch(FetchResult::notReady);

                inBackground(() -> stream.setCallback(callbackCount::incrementAndGet));
                gate.awaitEntered();

                Thread producer = inBackground(() -> {
                    stream.enqueue(FetchResult.of(entry)); // too late for the fetch in progress
                    stream.triggerSignalProgress();
                });

                awaitReturnedOrBlocked(List.of(producer));
                gate.release();

                await().until(() -> callbackCount.get() > 0);
                assertThat(stream.next()).contains(entry);
            }

            @Test
            void signalDuringAFetchThatReturnsAValueLosesNothing() throws InterruptedException {
                Entry<Message> fetched = entryOf("fetched");
                Entry<Message> signalled = entryOf("signalled");
                AtomicReference<Optional<Entry<Message>>> readerResult = new AtomicReference<>();

                stream.setCallback(callbackCount::incrementAndGet);

                Gate gate = stream.holdBackNextFetch(() -> FetchResult.of(fetched));
                Thread reader = inBackground(() -> readerResult.set(stream.next()));

                gate.awaitEntered();

                Thread producer = inBackground(() -> {
                    stream.enqueue(FetchResult.of(signalled));
                    stream.triggerSignalProgress();
                });

                awaitReturnedOrBlocked(List.of(producer));
                gate.release();

                assertThat(reader.join(Duration.ofSeconds(5))).isTrue();
                assertThat(readerResult.get()).contains(fetched);
                assertThat(stream.next()).contains(signalled);
            }
        }

        @Nested
        class WhenClosed {

            @Test
            void closeReturnsWithoutWaitingForFetchNext() throws InterruptedException {
                Gate gate = stream.holdBackNextFetch(FetchResult::notReady);

                inBackground(stream::next);
                gate.awaitEntered();

                assertReturnsPromptly(stream::close);
            }

            @Test
            void streamStateIsFinalAsSoonAsCloseReturns() throws InterruptedException {
                Gate gate = stream.holdBackNextFetch(() -> FetchResult.of(entryOf("fetched")));

                inBackground(stream::next);
                gate.awaitEntered();

                assertReturnsPromptly(stream::close);

                // the fetch is still in progress here
                assertThat(stream.next()).isEmpty();
                assertThat(stream.peek()).isEmpty();
                assertThat(stream.hasNextAvailable()).isFalse();
                assertThat(stream.isCompleted()).isTrue();
                assertThat(stream.error()).isEmpty();
            }

            @Test
            void onCompletedRunsOnceAfterTheFetchInProgressReturns() throws InterruptedException {
                Gate gate = stream.holdBackNextFetch(FetchResult::notReady);

                inBackground(stream::next);
                gate.awaitEntered();

                Thread closer = inBackground(stream::close);

                awaitReturnedOrBlocked(List.of(closer));

                assertThat(stream.onCompletedCount()).isZero();

                gate.release();

                assertThat(closer.join(Duration.ofSeconds(5))).isTrue();
                await().until(() -> stream.onCompletedCount() == 1);
                assertThat(stream.onCompletedDuringFetch()).isFalse();
            }

            @Test
            void valueReturnedByTheFetchInProgressIsNotAvailableAfterClose() throws InterruptedException {
                Entry<Message> fetched = entryOf("fetched");
                AtomicReference<Optional<Entry<Message>>> readerResult = new AtomicReference<>();
                Gate gate = stream.holdBackNextFetch(() -> FetchResult.of(fetched));
                Thread reader = inBackground(() -> readerResult.set(stream.next()));

                gate.awaitEntered();
                closeDuringTheFetch(gate);

                assertThat(reader.join(Duration.ofSeconds(5))).isTrue();

                // the call that started the fetch overlapped the close, so it may still return the value
                assertThat(readerResult.get()).satisfiesAnyOf(
                    result -> assertThat(result).isEmpty(),
                    result -> assertThat(result).contains(fetched)
                );
                assertThat(stream.next()).isEmpty();
                assertThat(stream.peek()).isEmpty();
            }

            @Test
            void errorReturnedByTheFetchInProgressDoesNotSetError() throws InterruptedException {
                Gate gate = stream.holdBackNextFetch(() -> FetchResult.error(new IllegalStateException("failure")));
                Thread reader = inBackground(stream::next);

                gate.awaitEntered();
                closeDuringTheFetch(gate);

                assertThat(reader.join(Duration.ofSeconds(5))).isTrue();
                assertThat(stream.error()).isEmpty();
            }

            @Test
            void exceptionThrownByTheFetchInProgressDoesNotSetError() throws InterruptedException {
                Gate gate = stream.holdBackNextFetch(() -> {
                    throw new IllegalStateException("failure");
                });
                Thread reader = inBackground(stream::next);

                gate.awaitEntered();
                closeDuringTheFetch(gate);

                assertThat(reader.join(Duration.ofSeconds(5))).isTrue();
                assertThat(stream.error()).isEmpty();
            }

            @Test
            void completedReturnedByTheFetchInProgressRunsOnCompletedOnce() throws InterruptedException {
                Gate gate = stream.holdBackNextFetch(FetchResult::completed);
                Thread reader = inBackground(stream::next);

                gate.awaitEntered();
                closeDuringTheFetch(gate);

                assertThat(reader.join(Duration.ofSeconds(5))).isTrue();
                await().until(() -> stream.onCompletedCount() == 1);
                assertThat(stream.onCompletedCount()).isEqualTo(1);
            }

            @Test
            void failingOnCompletedAfterTheFetchInProgressDoesNotSetError() throws InterruptedException {
                stream.failOnCompleted(new IllegalStateException("cleanup failed"));

                Gate gate = stream.holdBackNextFetch(FetchResult::notReady);
                Thread reader = inBackground(stream::next);

                gate.awaitEntered();
                closeDuringTheFetch(gate);

                assertThat(reader.join(Duration.ofSeconds(5))).isTrue();
                await().until(() -> stream.onCompletedCount() == 1);
                assertThat(stream.error()).isEmpty();
            }

            @Test
            void fetchNextIsNotCalledAgainAfterClose() throws InterruptedException {
                Gate gate = stream.holdBackNextFetch(FetchResult::notReady);
                Thread reader = inBackground(stream::next);

                gate.awaitEntered();
                closeDuringTheFetch(gate);

                assertThat(reader.join(Duration.ofSeconds(5))).isTrue();

                int fetches = stream.fetchNextCount();

                stream.next();
                stream.peek();
                stream.hasNextAvailable();

                assertThat(stream.fetchNextCount()).isEqualTo(fetches);
            }

            /**
             * Closes the stream from another thread while the fetch held back by the given gate is in
             * progress, then releases that fetch and waits for the close to have returned.
             */
            private void closeDuringTheFetch(Gate gate) throws InterruptedException {
                Thread closer = inBackground(stream::close);

                awaitReturnedOrBlocked(List.of(closer));
                gate.release();

                assertThat(closer.join(Duration.ofSeconds(5))).isTrue();
            }
        }

        /*
         * On Java 21 to 23, a virtual thread waiting inside synchronized code keeps its carrier thread.
         * These tests occupy every carrier of the default scheduler that way, and then check that a
         * virtual thread which can release the waiting ones still gets to run. On Java 24 and later,
         * waiting virtual threads always release their carrier, so the tests prove nothing there.
         */
        @Nested
        class WithVirtualThreadsOnJava21 {

            private final int carriers = Integer.getInteger(
                "jdk.virtualThreadScheduler.parallelism", Runtime.getRuntime().availableProcessors()
            );

            @BeforeEach
            void requireJavaVersionThatKeepsCarriersOfWaitingVirtualThreads() {
                assumeTrue(Runtime.version().feature() < 24, "only Java 21 to 23 keep the carrier");
            }

            @Test
            void virtualThreadsWaitingInFetchNextLeaveCarriersForOtherVirtualThreads() throws InterruptedException {
                List<Gate> gates = new ArrayList<>();

                try {
                    for (int i = 0; i < carriers; i++) {
                        GatedStream readerStream = new GatedStream();

                        gates.add(readerStream.holdBackNextFetch(FetchResult::notReady));
                        Thread.ofVirtual().start(readerStream::next);
                    }

                    for (Gate gate : gates) {
                        gate.awaitEntered();
                    }

                    Thread releaser = Thread.ofVirtual().start(() -> gates.forEach(Gate::release));

                    assertThat(releaser.join(Duration.ofSeconds(2))).isTrue();
                }
                finally {
                    gates.forEach(Gate::release);
                }
            }

            @Test
            void virtualThreadsSignallingDuringAFetchLeaveCarriersForOtherVirtualThreads() throws InterruptedException {
                Gate gate = stream.holdBackNextFetch(FetchResult::notReady);

                inBackground(stream::next); // a platform thread, which needs no carrier
                gate.awaitEntered();

                List<Thread> signallers = new ArrayList<>();

                for (int i = 0; i < carriers; i++) {
                    signallers.add(Thread.ofVirtual().start(stream::triggerSignalProgress));
                }

                awaitReturnedOrBlocked(signallers);

                Thread releaser = Thread.ofVirtual().start(gate::release);

                assertThat(releaser.join(Duration.ofSeconds(2))).isTrue();
            }
        }

        private static Thread inBackground(Runnable action) {
            return Thread.ofPlatform().start(action);
        }

        private static void assertReturnsPromptly(Runnable action) throws InterruptedException {
            assertThat(inBackground(action).join(Duration.ofSeconds(2))).as("returned within 2 seconds").isTrue();
        }

        /**
         * Waits until each of the given threads has either returned, or is blocked waiting to enter a
         * monitor, which is how a call that waits for the fetch in progress shows up.
         */
        private static void awaitReturnedOrBlocked(List<Thread> threads) {
            await().until(() -> threads.stream()
                                       .allMatch(t -> !t.isAlive() || t.getState() == Thread.State.BLOCKED));
        }
    }

    /*
     * When streams are stacked, the outer stream reads the inner one from its own fetchNext(). A signal
     * that reaches the inner stream from another thread during that read is delivered once the inner
     * fetch returns, on the reading thread, and so reaches the outer stream while its fetch is still in
     * progress. The outer fetch saw no entry, so it must not drop that signal.
     */
    @Nested
    class WhenStacked {

        @Test
        void twoStackedStreamsCallBackWhenAnEntryArrivedDuringTheInnerRead() {
            // given
            RacingSource source = new RacingSource(1);
            MessageStream<Message> stacked = source.filter(e -> true).filter(e -> true);
            AtomicInteger callbackCount = new AtomicInteger();
            stacked.setCallback(callbackCount::incrementAndGet);
            assertThat(stacked.next()).map(e -> e.message().payload()).contains("first");
            callbackCount.set(0);

            // when
            Optional<Entry<Message>> missed = stacked.next(); // "last" arrives after the source was found empty

            // then
            assertThat(source.raced()).isTrue();
            assertThat(missed).isEmpty();
            assertThat(callbackCount).hasPositiveValue();
            assertThat(stacked.next()).map(e -> e.message().payload()).contains("last");
        }

        @Test
        void sourcingChainCallsBackWhenAnEntryArrivedDuringTheInnerRead() {
            // given
            RacingSource source = new RacingSource(1);
            MessageStream<Message> chain = sourcingChainOf(source);
            AtomicInteger callbackCount = new AtomicInteger();
            chain.setCallback(callbackCount::incrementAndGet);
            assertThat(chain.next()).map(e -> e.message().payload()).contains("first");
            callbackCount.set(0);

            // when
            Optional<Entry<Message>> missed = chain.next(); // "last" arrives after the source was found empty

            // then
            assertThat(source.raced()).isTrue();
            assertThat(missed).isEmpty();
            assertThat(callbackCount).hasPositiveValue();
            assertThat(chain.next()).map(e -> e.message().payload()).contains("last");
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3})
        void sourcingChainIsReducedCompletelyWhicheverEmptyReadTheEntryArrivesDuring(int racingRead) {
            // given
            RacingSource source = new RacingSource(racingRead);
            MessageStream<Message> chain = sourcingChainOf(source);

            // when
            var reduced = chain.reduce(0, (count, entry) -> count + 1);

            // then
            assertThat(reduced).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(2);
        }

        /**
         * Composes the given {@code source} as an event store composes a sourcing stream, which an entity
         * lifecycle handler then reduces.
         */
        private static MessageStream<Message> sourcingChainOf(MessageStream<Message> source) {
            return source.onNext(e -> {
            }).filter(e -> true).onComplete(() -> {
            });
        }

        @Test
        void signalRaisedByAFetchThatReturnsAValueDoesNotInvokeTheCallback() {
            // given
            Entry<Message> entry = entryOf("value");
            AtomicInteger callbackCount = new AtomicInteger();
            ControllableStream stream = new ControllableStream() {
                @NonNull
                @Override
                protected FetchResult<Entry<Message>> fetchNext() {
                    triggerSignalProgress(); // raised by the fetch itself, which then returns what it announced
                    return FetchResult.of(entry);
                }
            };
            stream.setCallback(callbackCount::incrementAndGet);
            stream.next(); // consume the entry the probe of setCallback peeked
            callbackCount.set(0);

            // when
            Optional<Entry<Message>> result = stream.next();

            // then
            assertThat(result).contains(entry);
            assertThat(callbackCount).hasValue(0);
        }
    }

    /**
     * A {@link MessageStream} that does not extend {@link AbstractMessageStream}, like a source fed by a
     * connection, holding one entry initially. On the given read that finds it empty, a producer thread adds a
     * second entry and invokes the callback while that read is still in progress, after which the source completes.
     */
    private static final class RacingSource implements MessageStream<Message> {

        private final Queue<Entry<Message>> buffer = new ConcurrentLinkedQueue<>(List.of(entryOf("first")));
        private final AtomicBoolean raced = new AtomicBoolean();
        private final AtomicInteger emptyReads = new AtomicInteger();
        private final int racingRead;
        private volatile boolean producerDone;
        private volatile Runnable callback = () -> {
        };

        /**
         * @param racingRead the number of the read that finds the source empty during which the entry arrives,
         *                   starting at 1
         */
        RacingSource(int racingRead) {
            this.racingRead = racingRead;
        }

        boolean raced() {
            return raced.get();
        }

        @Override
        public Optional<Entry<Message>> next() {
            Entry<Message> entry = buffer.poll();

            if (entry == null && emptyReads.incrementAndGet() == racingRead && raced.compareAndSet(false, true)) {
                Thread producer = Thread.ofPlatform().start(() -> {
                    buffer.add(entryOf("last"));
                    producerDone = true;
                    callback.run();
                });

                try {
                    assertThat(producer.join(Duration.ofSeconds(5))).as("producer finished").isTrue();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }

            return Optional.ofNullable(entry);
        }

        @Override
        public Optional<Entry<Message>> peek() {
            return Optional.ofNullable(buffer.peek());
        }

        @Override
        public void setCallback(@NonNull Runnable callback) {
            this.callback = callback;

            if (hasNextAvailable() || isCompleted()) {
                callback.run();
            }
        }

        @Override
        public Optional<Throwable> error() {
            return Optional.empty();
        }

        @Override
        public boolean isCompleted() {
            return producerDone && buffer.isEmpty();
        }

        @Override
        public boolean hasNextAvailable() {
            return !buffer.isEmpty();
        }

        @Override
        public void close() {
        }
    }

    /**
     * Holds back one {@link AbstractMessageStream#fetchNext()} call until released, and then lets it
     * complete with a given outcome, which may also throw.
     */
    private static final class Gate {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final Supplier<FetchResult<Entry<Message>>> outcome;

        Gate(Supplier<FetchResult<Entry<Message>>> outcome) {
            this.outcome = outcome;
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("fetchNext() was entered").isTrue();
        }

        void release() {
            released.countDown();
        }

        FetchResult<Entry<Message>> pass() {
            entered.countDown();

            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("fetchNext() was never released.");
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                throw new IllegalStateException(e);
            }

            return outcome.get();
        }
    }

    /**
     * {@link AbstractMessageStream} whose {@link #fetchNext()} calls can be held back by a {@link Gate},
     * and which records how {@code fetchNext()} and {@code onCompleted()} calls relate to each other.
     */
    private static class GatedStream extends AbstractMessageStream<Message> {

        private final Queue<FetchResult<Entry<Message>>> results = new ConcurrentLinkedQueue<>();
        private final Queue<Gate> pendingGates = new ConcurrentLinkedQueue<>();
        private final List<Gate> allGates = new CopyOnWriteArrayList<>();
        private final AtomicInteger fetchNextCount = new AtomicInteger();
        private final AtomicInteger onCompletedCount = new AtomicInteger();
        private final AtomicBoolean fetching = new AtomicBoolean();
        private final AtomicBoolean overlappingFetches = new AtomicBoolean();
        private final AtomicBoolean onCompletedDuringFetch = new AtomicBoolean();
        private volatile RuntimeException onCompletedFailure;

        Gate holdBackNextFetch(Supplier<FetchResult<Entry<Message>>> outcome) {
            Gate gate = new Gate(outcome);

            pendingGates.add(gate);
            allGates.add(gate);

            return gate;
        }

        void releaseAllGates() {
            allGates.forEach(Gate::release);
        }

        void enqueue(FetchResult<Entry<Message>> result) {
            results.add(result);
        }

        void triggerSignalProgress() {
            signalProgress();
        }

        void failOnCompleted(RuntimeException failure) {
            this.onCompletedFailure = failure;
        }

        int fetchNextCount() {
            return fetchNextCount.get();
        }

        int onCompletedCount() {
            return onCompletedCount.get();
        }

        boolean overlappingFetches() {
            return overlappingFetches.get();
        }

        boolean onCompletedDuringFetch() {
            return onCompletedDuringFetch.get();
        }

        @NonNull
        @Override
        protected FetchResult<Entry<Message>> fetchNext() {
            if (!fetching.compareAndSet(false, true)) {
                overlappingFetches.set(true);
            }

            try {
                fetchNextCount.incrementAndGet();

                Gate gate = pendingGates.poll();

                if (gate != null) {
                    return gate.pass();
                }

                FetchResult<Entry<Message>> result = results.poll();

                return result != null ? result : FetchResult.notReady();
            }
            finally {
                fetching.set(false);
            }
        }

        @Override
        protected void onCompleted() {
            if (fetching.get()) {
                onCompletedDuringFetch.set(true);
            }

            onCompletedCount.incrementAndGet();

            RuntimeException failure = onCompletedFailure;

            if (failure != null) {
                throw failure;
            }
        }
    }
}
