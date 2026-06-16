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
 * Test class validating the {@link FlatMappedMessageStream} through the {@link MessageStreamTest} suite and
 * with additional flatMap-specific scenarios.
 *
 * @author John Hendrikx
 */
class FlatMappedMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        return new FlatMappedMessageStream<>(MessageStream.fromIterable(messages),
                                             entry -> MessageStream.just(entry.message()));
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        Assumptions.abort("FlatMappedMessageStream doesn't support explicit single-value streams");
        return null;
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        Assumptions.abort("FlatMappedMessageStream doesn't support explicit empty streams");
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
        void mapperReturningEmptyStreamSkipsEntry() {
            // given
            Message kept = createRandomMessage();
            Message skipped = createRandomMessage();
            MessageStream<Message> outer = MessageStream.fromIterable(List.of(skipped, kept));

            // when
            MessageStream<Message> stream = outer.flatMap(
                    entry -> entry.message().equals(skipped) ? MessageStream.empty() : MessageStream.just(entry.message())
            );

            // then
            assertThat(stream.next()).map(Entry::message).contains(kept);
            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isTrue();
        }

        @Test
        void allEntriesFilteredOutCompletesNormally() {
            // given
            MessageStream<Message> outer = MessageStream.fromIterable(List.of(createRandomMessage(), createRandomMessage()));

            // when
            MessageStream<Message> stream = outer.flatMap(entry -> MessageStream.empty());

            // then
            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isTrue();
            assertThat(stream.error()).isEmpty();
        }
    }

    @Nested
    class MultipleEmissionsPerEntry {

        @Test
        void mapperReturningTwoEntriesEmitsBoth() {
            // given
            Message a = createRandomMessage();
            Message b = createRandomMessage();
            Message c = createRandomMessage();
            Message d = createRandomMessage();

            // when -- each outer entry expands to two inner entries
            MessageStream<Message> stream = MessageStream.fromIterable(List.of(a, b))
                                                         .flatMap(entry -> MessageStream.fromItems(c, d));

            // then
            assertThat(stream.next()).map(Entry::message).contains(c);
            assertThat(stream.next()).map(Entry::message).contains(d);
            assertThat(stream.next()).map(Entry::message).contains(c);
            assertThat(stream.next()).map(Entry::message).contains(d);
            assertThat(stream.next()).isEmpty();
        }
    }

    @Nested
    class AsyncOuterStream {

        @Test
        void callbackFiredWhenOuterBecomesAvailable() {
            // given
            QueueMessageStream<Message> outer = new QueueMessageStream<>();
            Message msg = createRandomMessage();
            AtomicBoolean callbackCalled = new AtomicBoolean();
            MessageStream<Message> stream = outer.flatMap(entry -> MessageStream.just(entry.message()));
            stream.setCallback(() -> callbackCalled.set(true));

            // when
            assertThat(callbackCalled.getAndSet(false)).isFalse();
            outer.offer(msg, Context.empty());

            // then
            assertThat(callbackCalled.get()).isTrue();
            assertThat(stream.next()).map(Entry::message).contains(msg);
        }

        @Test
        void callbackFiredWhenOuterCompletes() {
            // given
            QueueMessageStream<Message> outer = new QueueMessageStream<>();
            AtomicBoolean callbackCalled = new AtomicBoolean();
            MessageStream<Message> stream = outer.flatMap(entry -> MessageStream.just(entry.message()));
            stream.setCallback(() -> callbackCalled.set(true));

            // when
            assertThat(callbackCalled.getAndSet(false)).isFalse();
            outer.seal();

            // then -- callback fires because outer sealed, notifying downstream
            assertThat(callbackCalled.get()).isTrue();

            // advance stream state to surface the completion
            assertThat(stream.next()).isEmpty();
            assertThat(stream.isCompleted()).isTrue();
        }
    }

    @Nested
    class AsyncInnerStream {

        @Test
        void callbackFiredWhenInnerBecomesAvailable() {
            // given
            Message outerMsg = createRandomMessage();
            Message innerMsg = createRandomMessage();
            QueueMessageStream<Message> innerStream = new QueueMessageStream<>();
            AtomicBoolean callbackCalled = new AtomicBoolean();

            MessageStream<Message> stream = MessageStream.just(outerMsg)
                                                         .flatMap(entry -> innerStream);
            stream.setCallback(() -> callbackCalled.set(true));

            // outer entry is consumed immediately but inner stream is not yet ready
            assertThat(stream.hasNextAvailable()).isFalse();
            assertThat(callbackCalled.getAndSet(false)).isFalse();

            // when inner becomes available
            innerStream.offer(innerMsg, Context.empty());
            innerStream.seal();

            // then
            assertThat(callbackCalled.get()).isTrue();
            assertThat(stream.next()).map(Entry::message).contains(innerMsg);
            assertThat(stream.next()).isEmpty();
        }
    }

    @Nested
    class ErrorPropagation {

        @Test
        void errorFromOuterStreamPropagates() {
            // given
            RuntimeException failure = new RuntimeException("outer failure");
            MessageStream<Message> stream = MessageStream.<Message>failed(failure)
                    .flatMap(entry -> MessageStream.just(entry.message()));

            // then
            assertThat(stream.error()).contains(failure);
        }

        @Test
        void errorFromInnerStreamPropagates() {
            // given
            RuntimeException failure = new RuntimeException("inner failure");
            MessageStream<Message> stream = MessageStream.just(createRandomMessage())
                                                         .flatMap(entry -> MessageStream.failed(failure));

            // when
            assertThat(stream.next()).isEmpty();

            // then
            assertThat(stream.error()).contains(failure);
        }

        @Test
        void subsequentEntriesNotEmittedAfterInnerError() {
            // given
            Message second = createRandomMessage();
            RuntimeException failure = new RuntimeException("inner failure");
            MessageStream<Message> stream = MessageStream.fromItems(createRandomMessage(), second)
                                                         .flatMap(entry -> entry.message().equals(second)
                                                                  ? MessageStream.just(entry.message())
                                                                  : MessageStream.failed(failure));

            // when
            assertThat(stream.next()).isEmpty();

            // then -- second entry never reached
            assertThat(stream.error()).contains(failure);
            assertThat(stream.isCompleted()).isTrue();
        }
    }

    @Nested
    class CloseAndCancel {

        @Test
        void closingStreamClosesInnerAndOuter() {
            // given
            QueueMessageStream<Message> outer = new QueueMessageStream<>();
            QueueMessageStream<Message> inner = new QueueMessageStream<>();
            outer.offer(createRandomMessage(), Context.empty());
            MessageStream<Message> stream = outer.flatMap(entry -> inner);

            // advance into the inner stream
            assertThat(stream.hasNextAvailable()).isFalse();

            // when
            stream.close();

            // then
            assertThat(stream.isCompleted()).isTrue();
            assertThat(inner.isCompleted()).isTrue();
            assertThat(outer.isCompleted()).isTrue();
        }
    }
}
