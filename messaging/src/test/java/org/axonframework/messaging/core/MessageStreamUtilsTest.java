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

import org.axonframework.common.DirectExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Test class validating {@link MessageStreamUtils}.
 */
class MessageStreamUtilsTest {

    private ExecutorService readers;

    @AfterEach
    void tearDown() {
        if (readers != null) {
            readers.shutdownNow();
        }
    }

    @Nested
    class Reduce {

        @Test
        void readsEntriesThatAreAvailableOnTheCallingThread() {
            // given
            MessageStream<Message> source = MessageStream.fromItems(message("one"), message("two"));
            AtomicReference<Thread> accumulatingThread = new AtomicReference<>();

            // when
            CompletableFuture<Integer> result = MessageStreamUtils.reduce(source, 0, (count, entry) -> {
                accumulatingThread.set(Thread.currentThread());
                return count + 1;
            });

            // then
            assertThat(result).isCompleted().isCompletedWithValue(2);
            assertThat(accumulatingThread).hasValue(Thread.currentThread());
        }

        @Test
        void readsEntriesThatArriveLaterOnAVirtualThreadByDefault() {
            // given
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            AtomicReference<Thread> accumulatingThread = new AtomicReference<>();
            CompletableFuture<Integer> result = MessageStreamUtils.reduce(source, 0, (count, entry) -> {
                accumulatingThread.set(Thread.currentThread());
                return count + 1;
            });

            // when
            source.offer(message("one"), Context.empty());
            source.seal();

            // then
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(result).isCompletedWithValue(1));
            assertThat(accumulatingThread.get()).isNotSameAs(Thread.currentThread());
            assertThat(accumulatingThread.get().isVirtual()).isTrue();
        }

        @Test
        void readsEntriesThatArriveLaterOnTheGivenExecutor() {
            // given
            readers = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "stream-reader"));
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            List<String> accumulatingThreads = new CopyOnWriteArrayList<>();
            CompletableFuture<Integer> result = MessageStreamUtils.reduce(source, 0, (count, entry) -> {
                accumulatingThreads.add(Thread.currentThread().getName());
                return count + 1;
            }, readers);

            // when
            source.offer(message("one"), Context.empty());
            source.offer(message("two"), Context.empty());
            source.seal();

            // then
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(result).isCompletedWithValue(2));
            assertThat(accumulatingThreads).isNotEmpty().allMatch("stream-reader"::equals);
        }

        @Test
        void completesExceptionallyWhenTheExecutorRejectsTheRead() {
            // given
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            CompletableFuture<Integer> result = MessageStreamUtils.reduce(source, 0, (count, entry) -> count + 1, task -> {
                throw new RejectedExecutionException("no capacity");
            });

            // when
            source.offer(message("one"), Context.empty());

            // then
            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(result::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
        }
    }

    @Nested
    class ReduceWithoutAnExecutor {

        @Test
        void readsEverythingOnTheCallingThreadWhenTheStreamReportsEntriesLater() throws Exception {
            // given a stream that has nothing available, and gets entries from another thread
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            Set<Thread> accumulatingThreads = ConcurrentHashMap.newKeySet();
            Thread producer = new Thread(() -> {
                sleep(100);
                source.offer(message("one"), Context.empty());
                sleep(100);
                source.offer(message("two"), Context.empty());
                source.seal();
            });

            // when
            producer.start();
            CompletableFuture<Integer> result = MessageStreamUtils.reduce(source, 0, (count, entry) -> {
                accumulatingThreads.add(Thread.currentThread());
                return count + 1;
            }, null);
            producer.join();

            // then the result is there when the call returns, and no other thread has read the stream
            assertThat(result).isCompletedWithValue(2);
            assertThat(accumulatingThreads).containsExactly(Thread.currentThread());
        }

        @Test
        void completesWithoutWaitingWhenTheEntriesAreAvailable() {
            // given
            MessageStream<Message> source = MessageStream.fromItems(message("one"), message("two"));

            // when
            CompletableFuture<Integer> result =
                    source.reduce(0, (count, entry) -> count + 1, null);

            // then
            assertThat(result).isCompletedWithValue(2);
        }

        @Test
        void completesExceptionallyWhenTheStreamFails() {
            // given
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            source.offer(message("one"), Context.empty());
            source.sealExceptionally(new IllegalArgumentException("broken"));

            // when
            CompletableFuture<Integer> result =
                    source.reduce(0, (count, entry) -> count + 1, null);

            // then
            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(result::join).hasRootCauseMessage("broken");
        }

        @Test
        void doesNotLoseEntriesOfAProducerThatRacesTheReduction() throws Exception {
            // given
            int count = 20_000;
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            Thread producer = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    source.offer(message(i), Context.empty());
                }
                source.seal();
            });

            // when
            producer.start();
            CompletableFuture<Integer> result =
                    source.reduce(0, (total, entry) -> total + 1, null);
            producer.join();

            // then
            assertThat(result).isCompletedWithValue(count);
        }

        @Test
        void givesUpAndClosesTheStreamWhenItReportsNoProgressInTime() {
            // given a stream that never gets another entry
            QueueMessageStream<Message> source = new QueueMessageStream<>();

            // when
            CompletableFuture<Integer> result = MessageStreamUtils.reduce(
                    source, 0, (total, entry) -> total + 1, null, Duration.ofMillis(100)
            );

            // then
            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(result::join).hasRootCauseInstanceOf(TimeoutException.class);
            assertThat(source.isCompleted()).isTrue();
        }

        @Test
        void failsAndKeepsTheInterruptStatusWhenTheCallingThreadIsInterrupted() throws Exception {
            // given a reduction that waits on a stream that never gets another entry
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            AtomicReference<CompletableFuture<Integer>> result = new AtomicReference<>();
            AtomicReference<Boolean> interrupted = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                result.set(source.reduce(0, (total, entry) -> total + 1, null));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            caller.start();
            await().until(() -> caller.getState() == Thread.State.TIMED_WAITING);

            // when
            caller.interrupt();
            caller.join(5_000);

            // then
            assertThat(result.get()).isCompletedExceptionally();
            assertThatThrownBy(() -> result.get().join()).hasRootCauseInstanceOf(InterruptedException.class);
            assertThat(interrupted).hasValue(true);
            assertThat(source.isCompleted()).isTrue();
        }

        @Test
        void isRejectedOnAThreadThatIsHandlingAProgressSignal() {
            // given a callback that reduces another stream, on the thread that signals
            QueueMessageStream<Message> signaling = new QueueMessageStream<>();
            QueueMessageStream<Message> other = new QueueMessageStream<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            signaling.setCallback(() -> {
                try {
                    other.reduce(0, (total, entry) -> total + 1, null);
                }
                catch (IllegalStateException e) {
                    failure.set(e);
                }
            });

            // when
            signaling.offer(message("one"), Context.empty());

            // then it would wait for a signal that this thread cannot deliver
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Nested
    class ReduceArguments {

        @Test
        void failsTheReadWhenTheExecutorRunsTasksOnTheSignallingThread() {
            // given an executor that runs the read inline, on the thread that signals
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            CompletableFuture<Integer> result =
                    source.reduce(0, (count, entry) -> count + 1, DirectExecutor.instance());

            // when
            source.offer(message("one"), Context.empty());

            // then reading on that thread is rejected
            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(result::join).hasRootCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        void readsLaterEntriesOnTheExecutorGivenToTheStreamMethod() {
            // given
            readers = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "stream-reader"));
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            Set<String> accumulatingThreads = ConcurrentHashMap.newKeySet();
            CompletableFuture<Integer> result = source.reduce(0, (count, entry) -> {
                accumulatingThreads.add(Thread.currentThread().getName());
                return count + 1;
            }, readers);

            // when
            source.offer(message("one"), Context.empty());
            source.seal();

            // then
            await().untilAsserted(() -> assertThat(result).isCompletedWithValue(1));
            assertThat(accumulatingThreads).containsExactly("stream-reader");
        }
    }

    private static Message message(Object payload) {
        return new GenericMessage(new MessageType("test"), payload);
    }
}
