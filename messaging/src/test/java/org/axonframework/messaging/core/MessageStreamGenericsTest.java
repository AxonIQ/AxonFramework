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
import org.axonframework.messaging.eventhandling.EventMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests if the generics on {@link MessageStream} methods are set correctly,
 * generally allowing methods to be called with subtypes for a message stream
 * of a specific type.
 *
 * @author John Hendrikx
 */
class MessageStreamGenericsTest {

    @Test
    void shouldAcceptGenericSubtypesForConcatWith() {
        MessageStream<SourceMessage> items = MessageStream.<SourceMessage>just(new SourceMessage.Snapshot())
            .concatWith(MessageStream.fromItems(new SourceMessage.Event(), new SourceMessage.Event()))
            .concatWith(MessageStream.just(new SourceMessage.ResumePosition()));

        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.Snapshot.class);
        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.Event.class);
        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.Event.class);
        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.ResumePosition.class);
        assertThat(items.next()).isEmpty();
    }

    @Test
    void shouldAcceptGenericSubtypesForOnErrorContinue() {
        MessageStream<SourceMessage> items = MessageStream.<SourceMessage>just(new SourceMessage.Snapshot())
            .concatWith(MessageStream.failed(new RuntimeException("oops")))
            .onErrorContinue(e -> MessageStream.just(new SourceMessage.ResumePosition()));

        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.Snapshot.class);
        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.ResumePosition.class);
        assertThat(items.next()).isEmpty();
    }

    @Test
    void shouldAcceptGenericSubtypesForFromItems() {
        MessageStream<SourceMessage> items = MessageStream.fromItems(
            new SourceMessage.Snapshot(),
            new SourceMessage.Event(),
            new SourceMessage.ResumePosition()
        );

        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.Snapshot.class);
        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.Event.class);
        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.ResumePosition.class);
        assertThat(items.next()).isEmpty();
    }

    @Test
    void shouldAcceptGenericSubtypesForFromIterable() {
        MessageStream<SourceMessage> items = MessageStream.fromIterable(List.of(
            new SourceMessage.Snapshot(),
            new SourceMessage.Event(),
            new SourceMessage.ResumePosition()
        ));

        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.Snapshot.class);
        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.Event.class);
        assertThat(items.next()).map(Entry::message).containsInstanceOf(SourceMessage.ResumePosition.class);
        assertThat(items.next()).isEmpty();
    }

    sealed interface SourceMessage extends Message {

        final class Event extends GenericMessage implements SourceMessage {
            public Event() {
                super(new MessageType(EventMessage.class), null);
            }
        }

        final class Snapshot extends GenericMessage implements SourceMessage {
            public Snapshot() {
                super(new MessageType(Snapshot.class), null);
            }
        }

        final class ResumePosition extends GenericMessage implements SourceMessage {
            public ResumePosition() {
                super(new MessageType(ResumePosition.class), null);
            }
        }
    }
}
