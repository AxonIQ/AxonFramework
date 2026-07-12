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

package org.axonframework.eventsourcing.eventstore.tracing;

import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventTagsSpanAttributesProviderTest {

    private final EventMessage event = EventTestUtils.asEventMessage("the-payload");

    @Nested
    class DefaultPrefix {

        @Test
        void addsAllResolvedTagsByDefault() {
            // given
            TagResolver resolver = e -> Set.of(Tag.of("courseId", "c-1"), Tag.of("studentId", "s-2"));

            // when
            var attributes = new EventTagsSpanAttributesProvider(resolver).provideForMessage(event, null);

            // then
            assertThat(attributes)
                    .containsEntry(EventTagsSpanAttributesProvider.EVENT_TAG_PREFIX + "courseId", "c-1")
                    .containsEntry(EventTagsSpanAttributesProvider.EVENT_TAG_PREFIX + "studentId", "s-2");
        }

        @Test
        void addsOnlyAllowlistedKeysWhenConfigured() {
            // given
            TagResolver resolver = e -> Set.of(Tag.of("courseId", "c-1"), Tag.of("secret", "hidden"));

            // when
            var attributes =
                    new EventTagsSpanAttributesProvider(resolver, Set.of("courseId")).provideForMessage(event, null);

            // then
            assertThat(attributes)
                    .containsEntry(EventTagsSpanAttributesProvider.EVENT_TAG_PREFIX + "courseId", "c-1")
                    .doesNotContainKey(EventTagsSpanAttributesProvider.EVENT_TAG_PREFIX + "secret");
        }
    }

    @Nested
    class CustomPrefix {

        @Test
        void usesTheGivenPrefixWhenOverridden() {
            // given
            TagResolver resolver = e -> Set.of(Tag.of("courseId", "c-1"));

            // when
            var attributes =
                    new EventTagsSpanAttributesProvider(resolver, "axon_tag_", Set.of()).provideForMessage(event, null);

            // then
            assertThat(attributes).containsEntry("axon_tag_courseId", "c-1");
        }
    }

    @Nested
    class NonEventMessage {

        @Test
        void contributesNothingForNonEventMessage() {
            // given
            Message command = new GenericCommandMessage(new MessageType("MyCommand"), "the-payload");
            TagResolver resolver = e -> Set.of(Tag.of("courseId", "c-1"));

            // when
            var attributes = new EventTagsSpanAttributesProvider(resolver).provideForMessage(command, null);

            // then
            assertThat(attributes).isEmpty();
        }
    }
}
