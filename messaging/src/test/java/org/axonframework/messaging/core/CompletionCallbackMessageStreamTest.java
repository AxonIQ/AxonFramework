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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.core.MessageStreamUtils.NO_OP_CALLBACK;

/**
 * Test class validating the {@link CompletionCallbackMessageStream} through the {@link MessageStreamTest} suite.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 */
class CompletionCallbackMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        return new CompletionCallbackMessageStream<>(MessageStream.fromIterable(messages), NO_OP_CALLBACK);
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        Assumptions.abort("CompletionCallbackMessageStream does not support explicit single-item streams");
        return null;
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        Assumptions.abort("CompletionCallbackMessageStream does not support explicit zero-item streams");
        return null;
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages,
                                                        RuntimeException failure) {
        return new CompletionCallbackMessageStream<>(MessageStream.fromIterable(messages)
                                                                  .concatWith(MessageStream.failed(failure)),
                                                     NO_OP_CALLBACK);
    }

    @Override
    protected Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"),
                                    "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Nested
    class WhenCompletionStatusRequested {

        @Test
        void withCompletionObservedThroughIsCompletedThenRunsTheHandlerExactlyOnce() {
            // given
            AtomicInteger handlerCount = new AtomicInteger();
            MessageStream<Message> testSubject = MessageStream.fromIterable(List.of(createRandomMessage()))
                                                              .onComplete(handlerCount::incrementAndGet);

            assertThat(testSubject.next()).isPresent();

            // when
            assertThat(testSubject.isCompleted()).isTrue();
            assertThat(testSubject.isCompleted()).isTrue();
            assertThat(testSubject.next()).isEmpty();

            // then
            assertThat(handlerCount).hasValue(1);
        }

        @Test
        void withCompletionObservedFromTheConsumerCallbackThenRunsTheHandlerOnceWithoutHoldingTheLock() {
            // given
            AtomicInteger handlerCount = new AtomicInteger();
            AtomicInteger callbackCount = new AtomicInteger();
            AtomicBoolean callbackRanUnderLock = new AtomicBoolean();
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            MessageStream<Message> testSubject = source.onComplete(handlerCount::incrementAndGet);

            testSubject.setCallback(() -> {
                callbackRanUnderLock.compareAndSet(false, Thread.holdsLock(testSubject));

                if (callbackCount.incrementAndGet() < 5) {
                    testSubject.isCompleted();
                }
            });

            assertThat(testSubject.next()).isEmpty();

            // when
            source.seal();

            // then
            assertThat(testSubject.isCompleted()).isTrue();
            assertThat(handlerCount).hasValue(1);
            assertThat(callbackRanUnderLock).isFalse();
        }

        @Test
        void withCompletionObservedDirectlyAfterSealingThenDoesNotInvokeTheCallbackWhileHoldingTheLock() {
            // given
            AtomicInteger handlerCount = new AtomicInteger();
            AtomicBoolean callbackRanUnderLock = new AtomicBoolean();
            QueueMessageStream<Message> source = new QueueMessageStream<>();
            MessageStream<Message> testSubject = source.onComplete(handlerCount::incrementAndGet);

            testSubject.setCallback(
                    () -> callbackRanUnderLock.compareAndSet(false, Thread.holdsLock(testSubject))
            );

            assertThat(testSubject.next()).isEmpty();
            source.seal();

            // when
            assertThat(testSubject.isCompleted()).isTrue();

            // then
            assertThat(handlerCount).hasValue(1);
            assertThat(callbackRanUnderLock).isFalse();
        }

        @Test
        void withHandlerAskingForTheCompletionStatusThenRunsTheHandlerOnce() {
            // given
            AtomicInteger handlerCount = new AtomicInteger();
            AtomicBoolean observedCompleted = new AtomicBoolean();
            AtomicReference<MessageStream<Message>> streamRef = new AtomicReference<>();
            MessageStream<Message> testSubject = MessageStream.fromIterable(List.of(createRandomMessage()))
                                                              .onComplete(() -> {
                                                                  handlerCount.incrementAndGet();
                                                                  observedCompleted.set(streamRef.get().isCompleted());
                                                              });

            streamRef.set(testSubject);

            // when
            assertThat(testSubject.next()).isPresent();
            assertThat(testSubject.next()).isEmpty();

            // then
            assertThat(testSubject.isCompleted()).isTrue();
            assertThat(handlerCount).hasValue(1);
            assertThat(observedCompleted).isTrue();
        }
    }
}
