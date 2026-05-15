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

import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.*;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link GenericTaggedEventMessage}.
 *
 * @author Steven van Beelen
 */
class GenericTaggedEventMessageTest {

    private static final Metadata TEST_METADATA = Metadata.with("key", "value");
    private static final EventMessage TEST_EVENT = EventTestUtils.asEventMessage("event")
                                                                 .withMetadata(TEST_METADATA);
    private static final Tag TEST_TAG = new Tag("key", "value");
    private static final Set<Tag> TEST_TAGS = Set.of(TEST_TAG);

    private GenericTaggedEventMessage<EventMessage> testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new GenericTaggedEventMessage<>(TEST_EVENT, TEST_TAGS);
    }

    @Test
    void containsExpectedData() {
        assertEquals(TEST_EVENT.identifier(), testSubject.event().identifier());
        assertEquals(TEST_EVENT.payload(), testSubject.event().payload());
        assertEquals(TEST_METADATA, testSubject.event().metadata());
        assertEquals(TEST_EVENT.timestamp(), testSubject.event().timestamp());
        assertEquals(TEST_TAGS, testSubject.tags());
    }

    @Test
    void assertEventAndTagsAreNotNull() {
        //noinspection DataFlowIssue
        assertThrows(IllegalArgumentException.class, () -> new GenericTaggedEventMessage<>(null, TEST_TAGS));
        //noinspection DataFlowIssue
        assertThrows(IllegalArgumentException.class, () -> new GenericTaggedEventMessage<>(TEST_EVENT, null));
    }

    @Test
    void updateTagsReturnsTaggedEventMessageWithChangedTags() {
        Tag testTag = new Tag("some-key", "some-value");

        Set<Tag> result = testSubject.updateTags(current -> {
                                         Set<Tag> newTags = new HashSet<>(current);
                                         newTags.add(testTag);
                                         return newTags;
                                     })
                                     .tags();

        assertTrue(result.contains(testTag));
        assertTrue(result.contains(TEST_TAG));
    }
}