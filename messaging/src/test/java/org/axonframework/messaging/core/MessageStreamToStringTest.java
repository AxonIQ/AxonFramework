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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@link #toString()} method of {@link AbstractMessageStream}.
 *
 * @author John Hendrikx
 */
class MessageStreamToStringTest {

    @Test
    void shouldHaveNiceToString() {
        MessageStream<Message> stream = MessageStream.just(createMessage())
            .concatWith(MessageStream.fromIterable(List.of(createMessage(), createMessage())).first())
            .onErrorContinue(t -> MessageStream.fromFuture(CompletableFuture.completedFuture(createMessage())))
            .onClose(() -> {});

        assertThat(stream.toString())
            .isEqualTo("CloseCallback{OnErrorContinue[P]{Concatenating{*SingleValue, TruncateFirst{Iterator[P]}}}}");

        assertThat(stream.next()).isNotEmpty();

        assertThat(stream.toString())
            .isEqualTo("CloseCallback{OnErrorContinue{Concatenating{*SingleValue, TruncateFirst{Iterator[P]}}}}");

        assertThat(stream.next()).isNotEmpty();

        assertThat(stream.toString())
            .isEqualTo("CloseCallback{OnErrorContinue{Concatenating{*TruncateFirst{Iterator}}}}");

        assertThat(stream.next()).isEmpty();

        assertThat(stream.toString())
            .isEqualTo("CloseCallback[COMPLETED]{OnErrorContinue[COMPLETED]{Concatenating[COMPLETED]{*TruncateFirst[COMPLETED]{Iterator[COMPLETED]}}}}");

    }

    private Message createMessage() {
        return new GenericMessage(new MessageType(String.class), null);
    }
}
