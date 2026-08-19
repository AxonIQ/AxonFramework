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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link MapMultiMessageStream} through the {@link MessageStreamTest} suite and
 * with additional mapMulti-specific scenarios.
 *
 * @author John Hendrikx
 */
class MapMultiMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        return new MapMultiMessageStream<>(MessageStream.fromIterable(messages),
                                          (entry, consumer) -> consumer.accept(entry));
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        Assumptions.abort("MapMultiMessageStream doesn't support explicit single-value streams");
        return null;
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        Assumptions.abort("MapMultiMessageStream doesn't support explicit empty streams");
        return null;
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages, RuntimeException failure) {
        return completedTestSubject(messages).concatWith(MessageStream.failed(failure));
    }

    @Override
    protected Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"), "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Nested
    class ZeroEmissionsPerEntry {

        @Test
        void mapperEmittingNothingSkipsEntry() {
            // given
            Message kept = createRandomMessage();
            Message skipped = createRandomMessage();
            MessageStream<Message> delegate = MessageStream.fromIterable(List.of(skipped, kept));

            // when
            MessageStream<Message> stream = delegate.mapMulti(
                    (entry, consumer) -> {
                        if (!entry.message().equals(skipped)) {
                            consumer.accept(entry);
                        }
                    }
            );

            // then
            assertThat(stream.next()).map(Entry::message).contains(kept);
            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isTrue();
        }

        @Test
        void allEntriesFilteredOutCompletesNormally() {
            // given
            MessageStream<Message> delegate = MessageStream.fromIterable(
                    List.of(createRandomMessage(), createRandomMessage()));

            // when
            MessageStream<Message> stream = delegate.mapMulti((entry, consumer) -> { /* emit nothing */ });

            // then
            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).isEmpty();
        }
    }

    @Nested
    class MultipleEmissionsPerEntry {

        @Test
        void mapperEmittingTwoValuesPerEntryExpandsStream() {
            // given
            Message a = createRandomMessage();
            Message b = createRandomMessage();
            Message expansion1 = createRandomMessage();
            Message expansion2 = createRandomMessage();

            // when -- each outer entry expands to two different messages
            MessageStream<Message> stream = MessageStream.fromItems(a, b)
                                                         .mapMulti((entry, consumer) -> {
                                                             consumer.accept(entry.map(m -> expansion1));
                                                             consumer.accept(entry.map(m -> expansion2));
                                                         });

            // then
            assertThat(stream.next()).map(Entry::message).contains(expansion1);
            assertThat(stream.next()).map(Entry::message).contains(expansion2);
            assertThat(stream.next()).map(Entry::message).contains(expansion1);
            assertThat(stream.next()).map(Entry::message).contains(expansion2);
            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isTrue();
        }

        @Test
        void bufferedEntriesReturnedBeforeNextDelegatePull() {
            // given -- verify that expansion entries are buffered and returned in order
            Message original = createRandomMessage();
            Message first = createRandomMessage();
            Message second = createRandomMessage();
            Message third = createRandomMessage();

            // when
            MessageStream<Message> stream = MessageStream.just(original)
                                                         .mapMulti((entry, consumer) -> {
                                                             consumer.accept(entry.map(m -> first));
                                                             consumer.accept(entry.map(m -> second));
                                                             consumer.accept(entry.map(m -> third));
                                                         });

            // then -- all three arrive in order before the stream completes
            assertThat(stream.next()).map(Entry::message).contains(first);
            assertThat(stream.next()).map(Entry::message).contains(second);
            assertThat(stream.next()).map(Entry::message).contains(third);
            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isTrue();
        }
    }

    @Nested
    class AsyncDelegate {

        @Test
        void callbackFiredWhenDelegateBecomesAvailable() {
            // given
            QueueMessageStream<Message> delegate = new QueueMessageStream<>();
            Message msg = createRandomMessage();
            AtomicBoolean callbackCalled = new AtomicBoolean();
            MessageStream<Message> stream = delegate.mapMulti((entry, consumer) -> consumer.accept(entry));
            stream.setCallback(() -> callbackCalled.set(true));

            // when
            assertThat(callbackCalled.getAndSet(false)).isFalse();
            delegate.offer(msg, Context.empty());

            // then
            assertThat(callbackCalled.get()).isTrue();
            assertThat(stream.next()).map(Entry::message).contains(msg);
        }

        @Test
        void callbackFiredWhenDelegateCompletes() {
            // given
            QueueMessageStream<Message> delegate = new QueueMessageStream<>();
            AtomicBoolean callbackCalled = new AtomicBoolean();
            MessageStream<Message> stream = delegate.mapMulti((entry, consumer) -> consumer.accept(entry));
            stream.setCallback(() -> callbackCalled.set(true));

            // when
            assertThat(callbackCalled.getAndSet(false)).isFalse();
            delegate.seal();

            // then -- callback fires because delegate sealed, notifying downstream
            assertThat(callbackCalled.get()).isTrue();

            // advance stream state to surface the completion
            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isTrue();
        }
    }

    @Nested
    class ErrorPropagation {

        @Test
        void errorFromDelegateStreamPropagates() {
            // given
            RuntimeException failure = new RuntimeException("delegate failure");
            MessageStream<Message> stream = MessageStream.<Message>failed(failure)
                    .mapMulti((entry, consumer) -> consumer.accept(entry));

            // then
            assertThat(stream.error()).contains(failure);
        }

        @Test
        void errorFromDelegateAfterEntriesPropagates() {
            // given
            RuntimeException failure = new RuntimeException("late failure");
            MessageStream<Message> stream = MessageStream.just(createRandomMessage())
                                                         .concatWith(MessageStream.failed(failure))
                                                         .mapMulti((entry, consumer) -> consumer.accept(entry));

            // when -- consume the first entry
            assertThat(stream.next()).isPresent();

            // then -- next pull surfaces the error
            assertThat(stream.next()).isEmpty();
            assertThat(stream.error()).contains(failure);
        }
    }

    @Nested
    class CloseAndCancel {

        @Test
        void closingStreamClosesDelegate() {
            // given
            QueueMessageStream<Message> delegate = new QueueMessageStream<>();
            MessageStream<Message> stream = delegate.mapMulti((entry, consumer) -> consumer.accept(entry));

            // when
            stream.close();

            // then
            assertThat(stream.isCompleted()).isTrue();
            assertThat(delegate.isCompleted()).isTrue();
        }
    }
}
