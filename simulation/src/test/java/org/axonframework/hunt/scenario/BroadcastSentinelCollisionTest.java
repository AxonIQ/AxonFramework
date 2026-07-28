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

package org.axonframework.hunt.scenario;

import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.SimpleEntry;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.ProcessorEventHandlingComponents;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a segmented processor does with an event whose sequence identifier happens to be the string the framework uses
 * as its broadcast sentinel.
 * <p>
 * The sentinel is a value, not a type: {@code Object BROADCAST = "BROADCAST"}, and the two places that act on it
 * compare it by {@code equals} and by {@code contains} against whatever a sequencing policy returned. Every
 * value-extracting policy the framework ships -- metadata, property, extraction, and the per-aggregate policy, whose
 * value is a bare entity identifier -- can therefore hand it that exact string without anybody intending a broadcast.
 * <p>
 * <b>This test asserts the collision, not the guarantee.</b> It is an expected-gap test: it passes while the sentinel
 * is a plain user-space string and turns red as soon as the sentinel becomes distinguishable from data -- a dedicated
 * type, an identity-compared object, or a namespaced value no payload would carry. A failure here is the good news.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class BroadcastSentinelCollisionTest {

    private static final QualifiedName EVENT_NAME = new QualifiedName("hunt.TopicEvent");
    private static final String COLLIDING_KEY = "BROADCAST";
    private static final String ORDINARY_KEY = "orders";
    private static final int SEGMENTS = 4;

    private static final ApplicationContext NO_COMPONENTS = new ApplicationContext() {
        @Override
        public <C> C component(Class<C> type, @Nullable String name) {
            throw new UnsupportedOperationException(
                    "The collision probe provides no component of type [" + type.getName() + "].");
        }
    };

    @Nested
    class AnEventKeyedByTheSentinelsOwnValue {

        @Test
        void isHandledOncePerSegmentWhileAnOrdinaryKeyIsHandledOnce() {
            // given a component sequenced by a value taken off the event, which is what every shipped
            // value-extracting policy does
            Map<String, AtomicInteger> handled = new ConcurrentHashMap<>();
            var component = SimpleEventHandlingComponent
                    .create("topic-projection", (event, context) -> Optional.of(topicOf(event)))
                    .subscribe(EVENT_NAME, (event, context) -> {
                        handled.computeIfAbsent(topicOf(event), key -> new AtomicInteger()).incrementAndGet();
                        return MessageStream.empty().cast();
                    });
            var components = new ProcessorEventHandlingComponents(List.of(component));

            // when the same two events are offered to every segment of the processor, exactly as a segmented
            // processor's work packages offer them
            for (Segment segment : segments()) {
                offer(components, segment, event(ORDINARY_KEY));
                offer(components, segment, event(COLLIDING_KEY));
            }

            // then the ordinary key was handled once across all segments, and the colliding key once per segment
            int ordinary = handled.getOrDefault(ORDINARY_KEY, new AtomicInteger()).get();
            int colliding = handled.getOrDefault(COLLIDING_KEY, new AtomicInteger()).get();
            System.out.println("BROADCAST COLLISION segments=" + SEGMENTS
                                       + " handled[" + ORDINARY_KEY + "]=" + ordinary
                                       + " handled[" + COLLIDING_KEY + "]=" + colliding);
            assertThat(ordinary)
                    .as("an ordinary sequence key hashes into exactly one segment")
                    .isEqualTo(1);
            assertThat(colliding)
                    .as("a sequence key equal to the sentinel's own value is handled in every segment")
                    .isEqualTo(SEGMENTS);
        }

        @Test
        void isIndistinguishableFromAnIntentionalBroadcast() {
            // given two components: one deliberately broadcasting, one merely carrying the same value as data
            AtomicInteger deliberate = new AtomicInteger();
            AtomicInteger accidental = new AtomicInteger();
            var deliberateComponent = SimpleEventHandlingComponent
                    .create("deliberate", (event, context) -> Optional.of(
                            org.axonframework.messaging.core.sequencing.SequencingPolicy.BROADCAST))
                    .subscribe(EVENT_NAME, (event, context) -> {
                        deliberate.incrementAndGet();
                        return MessageStream.empty().cast();
                    });
            var accidentalComponent = SimpleEventHandlingComponent
                    .create("accidental", (event, context) -> Optional.of(topicOf(event)))
                    .subscribe(EVENT_NAME, (event, context) -> {
                        accidental.incrementAndGet();
                        return MessageStream.empty().cast();
                    });

            // when each is driven over the same segments with an event whose data value is the sentinel's value
            for (Segment segment : segments()) {
                offer(new ProcessorEventHandlingComponents(List.of(deliberateComponent)), segment,
                      event(COLLIDING_KEY));
                offer(new ProcessorEventHandlingComponents(List.of(accidentalComponent)), segment,
                      event(COLLIDING_KEY));
            }

            // then the framework treated the two identically, so nothing downstream can tell intent from data
            System.out.println("BROADCAST COLLISION deliberate=" + deliberate.get()
                                       + " accidental=" + accidental.get());
            assertThat(accidental.get()).isEqualTo(deliberate.get()).isEqualTo(SEGMENTS);
        }
    }

    private static List<Segment> segments() {
        return Segment.splitBalanced(Segment.ROOT_SEGMENT, SEGMENTS - 1);
    }

    private static void offer(ProcessorEventHandlingComponents components, Segment segment, EventMessage event) {
        UnitOfWorkFactory factory = new SimpleUnitOfWorkFactory(NO_COMPONENTS);
        List<MessageStream.Entry<? extends EventMessage>> entries = new ArrayList<>();
        entries.add(new SimpleEntry<>(event));
        factory.create()
               .executeWithResult(context -> {
                   context.putResource(Segment.RESOURCE_KEY, segment);
                   return components.handle(entries, context).asCompletableFuture();
               })
               .orTimeout(30, TimeUnit.SECONDS)
               .join();
    }

    private static EventMessage event(String topic) {
        return new GenericEventMessage(new MessageType(EVENT_NAME), topic);
    }

    private static String topicOf(Message event) {
        return String.valueOf(event.payload());
    }
}
