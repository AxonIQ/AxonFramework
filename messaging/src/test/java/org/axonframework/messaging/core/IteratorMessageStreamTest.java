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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link IteratorMessageStream} through the {@link MessageStreamTest} suite.
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 */
class IteratorMessageStreamTest extends MessageStreamTest<Message> {

    @Override
    protected MessageStream<Message> completedTestSubject(List<Message> messages) {
        return MessageStream.fromIterable(messages);
    }

    @Override
    protected MessageStream.Single<Message> completedSingleStreamTestSubject(Message message) {
        Assumptions.abort("IteratorMessageStream doesn't support explicit single-value streams");
        return null;
    }

    @Override
    protected MessageStream.Empty<Message> completedEmptyStreamTestSubject() {
        Assumptions.abort("IteratorMessageStream doesn't support explicitly empty streams");
        return null;
    }

    @Override
    protected MessageStream<Message> failingTestSubject(List<Message> messages,
                                                        RuntimeException failure) {
        Assumptions.abort("IterableMessageStream doesn't support failures");
        return null;
    }

    @Override
    protected Message createRandomMessage() {
        return new GenericMessage(new MessageType("message"),
                                    "test-" + ThreadLocalRandom.current().nextInt(10000));
    }

    @Nested
    class WhenCompletionStatusRequested {

        @Test
        void withAnEmptySourceThenReportsCompletedWithoutBeingRead() {
            // given
            MessageStream<Message> testSubject = MessageStream.fromIterable(List.of());

            // when / then
            assertThat(testSubject.isCompleted()).isTrue();
        }

        @Test
        void withTheLastEntryHandedOutThenReportsCompletedWithoutBeingReadAgain() {
            // given
            Message message = createRandomMessage();
            MessageStream<Message> testSubject = MessageStream.fromIterable(List.of(message));

            // when
            assertThat(testSubject.next()).isPresent();

            // then
            assertThat(testSubject.isCompleted()).isTrue();
        }

        @Test
        void withEntriesLeftThenStaysOpen() {
            // given
            MessageStream<Message> testSubject =
                    MessageStream.fromIterable(List.of(createRandomMessage(), createRandomMessage()));

            // when
            assertThat(testSubject.next()).isPresent();

            // then
            assertThat(testSubject.isCompleted()).isFalse();
        }
    }
}
