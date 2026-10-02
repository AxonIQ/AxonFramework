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

package org.axonframework.eventsourcing.tracing.configuration;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamingCondition;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Verifies that enabling tracing does not narrow {@code EventStore} away from its {@code EventBus} and
 * {@code EventSink} roles. {@code EventStore} is registered under a single, most-specific
 * {@link org.axonframework.common.configuration.Component.Identifier}, and is reached, through assignability
 * matching, by decorators written only against the narrower {@code EventSink} / {@code EventBus} types it also
 * implements (see {@code MessagingTracingConfigurationEnhancer}).
 */
class EventStoreTracingConfigurationTest {

    private static final String PUBLISH_SPAN = "EventSink.publish";

    private final TestSpanFactory spanFactory = new TestSpanFactory();
    private AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Test
    void tracingDoesNotNarrowTheEventStoreAwayFromItsEventBusAndEventSinkRoles() {
        configuration = EventSourcingConfigurer.create()
                                              .componentRegistry(registry -> registry.registerComponent(
                                                      SpanFactory.class,
                                                      c -> spanFactory
                                              ))
                                              .start();

        EventStore eventStore = configuration.getComponent(EventStore.class);
        EventBus eventBus = configuration.getComponent(EventBus.class);
        EventSink eventSink = configuration.getComponent(EventSink.class);

        assertThat(eventBus).isSameAs(eventStore);
        assertThat(eventSink).isSameAs(eventStore);
    }

    @Test
    void publishingThroughTheEventStoreIsStillTracedWhenTracingIsEnabled() {
        configuration = EventSourcingConfigurer.create()
                                              .componentRegistry(registry -> registry.registerComponent(
                                                      SpanFactory.class,
                                                      c -> spanFactory
                                              ))
                                              .start();
        EventStore eventStore = configuration.getComponent(EventStore.class);
        EventMessage event = EventTestUtils.createEvent(0);
        String expectedSpanName = PUBLISH_SPAN + " " + event.type().qualifiedName().name();

        joinAndUnwrap(eventStore.publish(null, List.of(event)));

        spanFactory.verifySpanCompleted(expectedSpanName);
    }

    @Test
    void eventStoreSpecificOperationsStillWorkWhenTracingIsEnabled() {
        configuration = EventSourcingConfigurer.create()
                                              .componentRegistry(registry -> registry.registerComponent(
                                                      SpanFactory.class,
                                                      c -> spanFactory
                                              ))
                                              .start();
        EventStore eventStore = configuration.getComponent(EventStore.class);
        StreamingCondition condition = StreamingCondition.startingFrom(TrackingToken.FIRST);

        // EventSink/EventBus have no such method -- if tracing had narrowed the component down to a bare
        // EventSink implementation, this would throw a ClassCastException at component resolution time.
        assertDoesNotThrow(() -> eventStore.open(condition, null));
    }
}
