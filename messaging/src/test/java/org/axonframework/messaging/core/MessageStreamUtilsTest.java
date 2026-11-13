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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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

    private static Message message(Object payload) {
        return new GenericMessage(new MessageType("test"), payload);
    }
}
