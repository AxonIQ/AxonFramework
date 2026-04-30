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

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.eventsourcing.eventstore.AnnotationBasedTagResolver;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.InterceptingEventStore;
import org.axonframework.eventsourcing.eventstore.SnapshotCapableEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.StorageEngineBackedEventStore;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.configuration.MessagingConfigurationDefaults;
import org.axonframework.messaging.core.interception.DispatchInterceptorRegistry;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventBus;
import org.axonframework.messaging.eventhandling.configuration.EventBusConfigurationDefaults;

import java.util.List;
import java.util.Optional;

/**
 * A {@link ConfigurationEnhancer} registering the default components of the {@link EventSourcingConfigurer}.
 * <p>
 * This enhancer disables the {@link EventBusConfigurationDefaults} to prevent duplicate {@link EventBus} registration,
 * as the {@link EventStore} implementation serves as the EventBus in event sourcing scenarios.
 * <p>
 * Will only register the following components <b>if</b> there is no component registered for the given class yet:
 * <ul>
 *     <li>Registers a {@link AnnotationBasedTagResolver} for class {@link TagResolver}</li>
 *     <li>Registers a {@link InMemoryEventStorageEngine} for class {@link EventStorageEngine}</li>
 *     <li>Registers a {@link StorageEngineBackedEventStore} for class {@link EventStore}</li>
 * </ul>
 * Furthermore, this enhancer will decorate the:
 * <ul>
 *     <li>The {@link EventStore} in a {@link InterceptingEventStore} <b>if</b> there are any
 *     {@link MessageDispatchInterceptor MessageDispatchInterceptors} present in the {@link DispatchInterceptorRegistry}.</li>
 * </ul>
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class EventSourcingConfigurationDefaults implements ConfigurationEnhancer {

    /**
     * The order of {@code this} enhancer compared to others, equal to 100 positions before
     * {@link MessagingConfigurationDefaults} (thus, {@link MessagingConfigurationDefaults#ENHANCER_ORDER} - 100).
     */
    public static final int ENHANCER_ORDER = MessagingConfigurationDefaults.ENHANCER_ORDER - 100;

    @Override
    public int order() {
        return ENHANCER_ORDER;
    }

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.disableEnhancer(EventBusConfigurationDefaults.class);
        // Register components
        registry.registerIfNotPresent(TagResolver.class, EventSourcingConfigurationDefaults::defaultTagResolver)
                .registerIfNotPresent(EventStorageEngine.class,
                                      EventSourcingConfigurationDefaults::defaultEventStorageEngine)
                .registerIfNotPresent(EventStore.class, EventSourcingConfigurationDefaults::simpleEventStore);
        registry.registerDecorator(
                EventStore.class,
                InterceptingEventStore.DECORATION_ORDER,
                (config, name, delegate) -> {
                    List<MessageDispatchInterceptor<? super EventMessage>> dispatchInterceptors =
                            config.getComponent(DispatchInterceptorRegistry.class)
                                  .eventInterceptors(config, EventStore.class, name);

                    return dispatchInterceptors.isEmpty()
                            ? delegate
                            : new InterceptingEventStore(delegate, dispatchInterceptors);
                }
        );
    }

    private static TagResolver defaultTagResolver(Configuration configuration) {
        return new AnnotationBasedTagResolver();
    }

    private static EventStorageEngine defaultEventStorageEngine(Configuration config) {
        return new InMemoryEventStorageEngine();
    }

    private static StorageEngineBackedEventStore simpleEventStore(Configuration config) {
        EventStorageEngine engine = config.getComponent(EventStorageEngine.class);
        Optional<SnapshotStore> snapshotStore = config.getOptionalComponent(SnapshotStore.class);

        EventStorageEngine effectiveEngine = snapshotStore.isEmpty() || engine instanceof SnapshotStore
            ? engine
            : new SnapshotCapableEventStorageEngine(engine, snapshotStore.orElseThrow());

        return new StorageEngineBackedEventStore(
                effectiveEngine,
                new SimpleEventBus(),
                config.getComponent(TagResolver.class)
        );
    }
}
