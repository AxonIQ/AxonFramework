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

import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventstreaming.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the caching {@link TagResolver#resolve(EventMessage, ProcessingContext)} default method.
 */
class TagResolverTest {

    private final AtomicInteger resolveCalls = new AtomicInteger();

    @Test
    void resolvesEachEventOnlyOncePerContext() {
        // given
        Tag tag = new Tag("key", "value");
        TagResolver resolver = event -> {
            resolveCalls.incrementAndGet();
            return Set.of(tag);
        };
        ProcessingContext context = new StubProcessingContext();
        EventMessage event = new GenericEventMessage(new MessageType("SomeEvent"), "payload");

        // when
        Set<Tag> first = resolver.resolve(event, context);
        Set<Tag> second = resolver.resolve(event, context);

        // then
        assertThat(first).containsExactly(tag);
        assertThat(second).isEqualTo(first);
        assertThat(resolveCalls.get()).isEqualTo(1);
    }

    @Test
    void resolvesDistinctEventsSeparately() {
        // given
        TagResolver resolver = event -> {
            resolveCalls.incrementAndGet();
            return Set.of(new Tag("id", event.identifier()));
        };
        ProcessingContext context = new StubProcessingContext();
        EventMessage eventOne = new GenericEventMessage(new MessageType("SomeEvent"), "one");
        EventMessage eventTwo = new GenericEventMessage(new MessageType("SomeEvent"), "two");

        // when
        Set<Tag> tagsOne = resolver.resolve(eventOne, context);
        Set<Tag> tagsTwo = resolver.resolve(eventTwo, context);

        // then
        assertThat(tagsOne).containsExactly(new Tag("id", eventOne.identifier()));
        assertThat(tagsTwo).containsExactly(new Tag("id", eventTwo.identifier()));
        assertThat(resolveCalls.get()).isEqualTo(2);
    }

    @Test
    void cacheIsScopedToTheGivenContext() {
        // given
        TagResolver resolver = event -> {
            resolveCalls.incrementAndGet();
            return Set.of(new Tag("key", "value"));
        };
        EventMessage event = new GenericEventMessage(new MessageType("SomeEvent"), "payload");

        // when — resolving the same event in two different contexts
        resolver.resolve(event, new StubProcessingContext());
        resolver.resolve(event, new StubProcessingContext());

        // then — each context resolves independently
        assertThat(resolveCalls.get()).isEqualTo(2);
    }

    @Test
    void resolvesWithoutCachingWhenContextIsNull() {
        // given
        Tag tag = new Tag("key", "value");
        TagResolver resolver = event -> {
            resolveCalls.incrementAndGet();
            return Set.of(tag);
        };
        EventMessage event = new GenericEventMessage(new MessageType("SomeEvent"), "payload");

        // when
        Set<Tag> first = resolver.resolve(event, null);
        Set<Tag> second = resolver.resolve(event, null);

        // then
        assertThat(first).containsExactly(tag);
        assertThat(second).isEqualTo(first);
        assertThat(resolveCalls.get()).isEqualTo(2);
    }
}
