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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link OnErrorContinueMessageStream} through the {@link MessageStreamTest} suite.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 */
class OnErrorContinueMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        return new OnErrorContinueMessageStream<>(MessageStream.fromIterable(messages),
                                                  error -> MessageStream.empty().cast());
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        Assumptions.abort("OnErrorContinueMessageStream doesn't support explicit single-value streams");
        return null;
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        Assumptions.abort("OnErrorContinueMessageStream doesn't support explicitly empty streams");
        return null;
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages,
                                                        RuntimeException failure) {
        return new OnErrorContinueMessageStream<>(MessageStream.fromIterable(messages)
                                                               .concatWith(MessageStream.failed(new RuntimeException(
                                                                       "Wrong failure"))),
                                                  error -> MessageStream.failed(failure));
    }

    @Override
    protected Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"),
                                    "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Disabled("OnErrorContinueMessageStream is supposed to continue without error.")
    @Override
    void shouldResultInFailedStreamWhenCompletionCallbackThrowsAnException_asFlux() {

    }

    @Nested
    class OnNext {

        @Test
        void shouldNotReturnSpuriousEmptyWhenErrorIsDiscoveredDuringNextCall() {
            // given
            // A stream of items followed by a failure, with an error continuation
            Message first = createRandomMessage();
            Message second = createRandomMessage();
            Message continuation = createRandomMessage();

            MessageStream<Message> subject = new OnErrorContinueMessageStream<>(
                    MessageStream.fromItems(first, second)
                                 .concatWith(MessageStream.failed(new RuntimeException("oops"))),
                    error -> MessageStream.just(continuation)
            );

            // when / then
            // Items before the error are returned normally
            assertThat(subject.next()).map(Entry::message).contains(first);
            assertThat(subject.next()).map(Entry::message).contains(second);
            // The call that discovers the error should return the continuation's first item,
            // not an empty Optional followed by the continuation item on the next call
            assertThat(subject.next()).map(Entry::message).contains(continuation);
            assertThat(subject.next()).isEmpty();
        }
    }
}