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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link LazyMessageStream} through the {@link MessageStreamTest} suite.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
class LazyMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        return new LazyMessageStream<>(() -> MessageStream.fromIterable(messages));
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        return new LazyMessageStream.Single<>(() -> MessageStream.just(message));
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        return new LazyMessageStream.Empty<>(() -> MessageStream.empty().cast());
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages, RuntimeException failure) {
        return new LazyMessageStream<>(() -> MessageStream.fromIterable(messages)
                                                          .concatWith(MessageStream.failed(failure)));
    }

    @Override
    protected Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"),
                                  "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Nested
    class LazyBehaviour {

        @Test
        void supplierIsNotCalledOnConstruction() {
            // given
            AtomicInteger callCount = new AtomicInteger();

            // when
            new LazyMessageStream<>(() -> {
                callCount.incrementAndGet();
                return MessageStream.empty();
            });

            // then
            assertThat(callCount.get()).isZero();
        }

        @Test
        void supplierIsCalledOnFirstNextRequest() {
            // given
            AtomicInteger callCount = new AtomicInteger();
            var testSubject = new LazyMessageStream<Message>(() -> {
                callCount.incrementAndGet();
                return MessageStream.just(createRandomMessage());
            });

            // when
            testSubject.next();

            // then
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        void supplierIsCalledOnFirstHasNextAvailableRequest() {
            // given
            AtomicInteger callCount = new AtomicInteger();
            var testSubject = new LazyMessageStream<Message>(() -> {
                callCount.incrementAndGet();
                return MessageStream.just(createRandomMessage());
            });

            // when
            testSubject.hasNextAvailable();

            // then
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        void supplierIsCalledAtMostOnce() {
            // given
            AtomicInteger callCount = new AtomicInteger();
            var testSubject = new LazyMessageStream<Message>(() -> {
                callCount.incrementAndGet();
                return MessageStream.just(createRandomMessage());
            });

            // when
            testSubject.next();
            testSubject.next();
            testSubject.hasNextAvailable();

            // then
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        void closeBeforeFirstAccessDoesNotInvokeSupplier() {
            // given
            AtomicInteger callCount = new AtomicInteger();
            var testSubject = new LazyMessageStream<Message>(() -> {
                callCount.incrementAndGet();
                return MessageStream.just(createRandomMessage());
            });

            // when
            testSubject.close();

            // then
            assertThat(callCount.get()).isZero();
        }

        @Test
        void supplierExceptionPropagatesAsFailed() {
            // given
            var failure = new MockException("supplier failed");
            var testSubject = new LazyMessageStream<Message>(() -> { throw failure; });

            // when
            testSubject.next();

            // then
            assertThat(testSubject.error()).containsInstanceOf(MockException.class);
            assertThat(testSubject.isCompleted()).isTrue();
        }

        @Test
        void setCallbackOnConcatenatedStreamDoesNotInitializeLazySecondStream() {
            // given
            AtomicInteger supplierCallCount = new AtomicInteger();
            // first stream has a message, so it is not yet completed when setCallback is called
            MessageStream<Message> firstStream = MessageStream.just(createRandomMessage());

            // when
            MessageStream<Message> concatenated = firstStream.concatWith(() -> {
                supplierCallCount.incrementAndGet();
                return MessageStream.just(createRandomMessage());
            });
            concatenated.setCallback(() -> {});

            // then
            assertThat(supplierCallCount.get()).isZero();
        }
    }
}
