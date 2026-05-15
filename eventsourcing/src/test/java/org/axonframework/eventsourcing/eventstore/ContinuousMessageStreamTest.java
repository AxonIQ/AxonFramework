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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStream.Empty;
import org.axonframework.messaging.core.MessageStream.Single;
import org.axonframework.messaging.core.MessageStreamTest;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for {@link ContinuousMessageStream}.
 *
 * @author John Hendrikx
 */
public class ContinuousMessageStreamTest extends MessageStreamTest<EventMessage> {

    @Override
    protected MessageStream<EventMessage> completedTestSubject(List<EventMessage> messages) {
        AtomicBoolean called = new AtomicBoolean(false);
        return new ContinuousMessageStream<>(
            () -> called.getAndSet(true) ? List.of() : messages,
            m -> new SimpleEntry<>(m),
            (ms, r) -> () -> true
        );
    }

    @Override
    protected Single<EventMessage> completedSingleStreamTestSubject(EventMessage message) {
        Assumptions.abort("doesn't support explicit single-value streams");
        return null;
    }

    @Override
    protected Empty<EventMessage> completedEmptyStreamTestSubject() {
        Assumptions.abort("doesn't support explicitly empty streams");
        return null;
    }

    @Override
    protected MessageStream<EventMessage> failingTestSubject(List<EventMessage> messages, RuntimeException failure) {
        AtomicBoolean called = new AtomicBoolean(false);
        return new ContinuousMessageStream<>(
            () -> {
                if (!called.getAndSet(true) && !messages.isEmpty()) {
                    return messages;
                }
                throw failure;
            },
            m -> new SimpleEntry<>(m),
            (ms, r) -> () -> true
        );
    }

    @Override
    protected EventMessage createRandomMessage() {
        return new GenericEventMessage(new MessageType("message"), UUID.randomUUID().toString());
    }

    @Override
    protected boolean isBoundedStream() {
        return false;
    }

    @Nested
    class WhenFetcherAdvancesCursor {

        @Test
        void fetchMore_shouldAdvanceStartPositionWhenNoItemsReturned() {
            // given
            EventMessage cursorItem = new GenericEventMessage(new MessageType("cursor"), UUID.randomUUID().toString());
            List<EventMessage> capturedCursors = new ArrayList<>();
            AtomicReference<EventMessage> cursorRef = new AtomicReference<>(null);

            ContinuousMessageStream<EventMessage> stream = new ContinuousMessageStream<>(
                () -> {
                    capturedCursors.add(cursorRef.get());
                    if (capturedCursors.size() == 1) {
                        // first call: no matching items, but advance cursor
                        cursorRef.set(cursorItem);
                        return List.of();
                    }
                    // second call: still nothing - stream should wait
                    return List.of();
                },
                m -> new SimpleEntry<>(m),
                (ms, r) -> () -> true
            );

            // when
            // both calls find no data and each independently triggers fetchMore()
            stream.peek();
            stream.peek();

            // then
            assertThat(capturedCursors).hasSize(2);
            assertThat(capturedCursors.get(0)).isNull();             // first call: cursor not yet set
            assertThat(capturedCursors.get(1)).isSameAs(cursorItem); // second call: closure advanced the cursor
        }
    }
}
