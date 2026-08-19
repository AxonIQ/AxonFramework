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

package org.axonframework.eventsourcing.configuration;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.eventstore.AnnotationBasedTagResolver;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.InterceptingEventStore;
import org.axonframework.eventsourcing.eventstore.SnapshotCapableEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.axonframework.messaging.eventstreaming.Tag;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.*;
import org.mockito.junit.jupiter.*;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link EventSourcingConfigurationDefaults}.
 *
 * @author Steven van Beelen
 * @author John Hendrikx
 */
@ExtendWith(MockitoExtension.class)
class EventSourcingConfigurationDefaultsTest {

    private EventSourcingConfigurationDefaults testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new EventSourcingConfigurationDefaults();
    }

    @Test
    void orderEqualsEnhancerOrderConstant() {
        assertEquals(EventSourcingConfigurationDefaults.ENHANCER_ORDER, testSubject.order());
    }

    @Test
    void enhanceSetsExpectedDefaultsInAbsenceOfTheseComponents() {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        Configuration resultConfig = configurer.build();

        assertInstanceOf(AnnotationBasedTagResolver.class, resultConfig.getComponent(TagResolver.class));
        assertInstanceOf(InMemoryEventStorageEngine.class,
                         resultConfig.getComponent(EventStorageEngine.class));

        // Intercepting at all times, since we have a MessageOriginProvider that leads to the CorrelationDataInterceptor
        EventStore eventStore = resultConfig.getComponent(EventStore.class);
        assertInstanceOf(InterceptingEventStore.class, eventStore);

        EventSink eventSink = resultConfig.getComponent(EventSink.class);
        EventBus eventBus = resultConfig.getComponent(EventBus.class);
        // By default, the Event Store and the Event Sink should be the same instance.
        assertEquals(eventBus, eventSink);
        assertInstanceOf(InterceptingEventStore.class, eventSink);
        assertInstanceOf(InterceptingEventStore.class, eventBus);

        StreamableEventSource streamableEventSource = resultConfig.getComponent(StreamableEventSource.class);
        assertEquals(eventBus, streamableEventSource);

        SubscribableEventSource subscribableEventSource = resultConfig.getComponent(SubscribableEventSource.class);
        assertEquals(eventBus, subscribableEventSource);
    }

    @Test
    void enhanceSetsEventStoreAsEventSink() {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        Configuration resultConfig = configurer.build();

        EventSink eventSink = resultConfig.getComponent(EventSink.class);
        assertInstanceOf(InterceptingEventStore.class, eventSink);
    }

    @Test
    void enhanceSetsEventBusAsSubscribableEventSource() {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        Configuration resultConfig = configurer.build();

        EventBus eventBus = resultConfig.getComponent(EventBus.class);
        SubscribableEventSource subscribableEventSource = resultConfig.getComponent(SubscribableEventSource.class);
        assertEquals(eventBus, subscribableEventSource);
    }

    @Test
    void enhanceOnlySetsDefaultsThatAreNotPresentYet() {
        TestTagResolver testTagResolver = new TestTagResolver();

        ApplicationConfigurer configurer = MessagingConfigurer.create();
        configurer.componentRegistry(cr -> cr.registerComponent(TagResolver.class, c -> testTagResolver));
        configurer.componentRegistry(cr -> cr.registerEnhancer(testSubject));

        TagResolver configuredTagResolver = configurer.build()
                                                      .getComponent(TagResolver.class);

        assertEquals(testTagResolver, configuredTagResolver);
    }

    @Test
    void decoratesEventStorageEngineAsSnapshotCapableWhenSnapshotStoreIsPresent() {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        configurer.componentRegistry(cr -> cr.registerComponent(SnapshotStore.class, c -> new InMemorySnapshotStore()));
        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(EventStorageEngine.class))
                .isInstanceOf(SnapshotCapableEventStorageEngine.class);
    }

    @Test
    void doesNotDecorateEventStorageEngineWhenNoSnapshotStoreIsPresent() {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(EventStorageEngine.class))
                .isNotInstanceOf(SnapshotCapableEventStorageEngine.class);
    }

    @Test
    void doesNotDecorateEventStorageEngineWhenSnapshotStoreIsSameInstance(
            @Mock(extraInterfaces = SnapshotStore.class) InMemoryEventStorageEngine snapshotAwareEngine
    ) {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        configurer.componentRegistry(cr -> cr.registerComponent(EventStorageEngine.class, c -> snapshotAwareEngine)
                                             .registerComponent(SnapshotStore.class,
                                                                c -> (SnapshotStore) snapshotAwareEngine));
        Configuration resultConfig = configurer.build();

        // wrapping would prevent the engine's single-round-trip optimisation
        assertThat(resultConfig.getComponent(EventStorageEngine.class))
                .isNotInstanceOf(SnapshotCapableEventStorageEngine.class);
    }

    @Test
    void decoratesEventStorageEngineWhenSnapshotStoreIsDifferentInstance_evenIfEngineImplementsSnapshotStore(
            @Mock(extraInterfaces = SnapshotStore.class) InMemoryEventStorageEngine snapshotAwareEngine
    ) {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        configurer.componentRegistry(cr -> cr.registerComponent(EventStorageEngine.class, c -> snapshotAwareEngine)
                                             .registerComponent(SnapshotStore.class, c -> new InMemorySnapshotStore()));
        Configuration resultConfig = configurer.build();

        // snapshot reads must be routed to the registered SnapshotStore, not the engine itself
        assertThat(resultConfig.getComponent(EventStorageEngine.class))
                .isInstanceOf(SnapshotCapableEventStorageEngine.class);
    }

    @Test
    void decoratorsEventStoreAsInterceptorEventStoreWhenDispatchInterceptorIsPresent(
            @Mock MessageDispatchInterceptor<Message> mockInterceptor) {
        ApplicationConfigurer configurer = EventSourcingConfigurer
                .create()
                .messaging(m -> m.registerDispatchInterceptor(c -> mockInterceptor));
        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(EventStore.class)).isInstanceOf(InterceptingEventStore.class);
    }

    private static class TestTagResolver implements TagResolver {

        @Override
        public @NonNull Set<Tag> resolve(@NonNull EventMessage event) {
            throw new UnsupportedOperationException();
        }
    }
}