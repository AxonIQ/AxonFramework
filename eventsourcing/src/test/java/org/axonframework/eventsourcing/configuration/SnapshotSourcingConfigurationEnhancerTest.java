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
import org.axonframework.common.configuration.BaseModule;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SnapshotCapableEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link SnapshotSourcingConfigurationEnhancer}.
 *
 * @author Laura Devriendt
 */
class SnapshotSourcingConfigurationEnhancerTest {

    private final SnapshotSourcingConfigurationEnhancer testSubject = new SnapshotSourcingConfigurationEnhancer();

    @Test
    void orderEqualsEnhancerOrderConstant() {
        assertThat(testSubject.order()).isEqualTo(SnapshotSourcingConfigurationEnhancer.ENHANCER_ORDER);
    }

    // The defaults register this enhancer while they are themselves being invoked, and an enhancer registered that way
    // is invoked in its order relative to the enhancers that have not run yet. A lower order would place it before
    // enhancers that already ran.
    @Test
    void orderExceedsTheEventSourcingDefaults() {
        assertThat(SnapshotSourcingConfigurationEnhancer.ENHANCER_ORDER)
                .isGreaterThan(EventSourcingConfigurationDefaults.ENHANCER_ORDER);
    }

    @Test
    void decoratesTheEventStorageEngineWhenASnapshotStoreIsPresent() {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        configurer.componentRegistry(cr -> cr.registerComponent(SnapshotStore.class, c -> new InMemorySnapshotStore()));

        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(EventStorageEngine.class))
                .isInstanceOf(SnapshotCapableEventStorageEngine.class);
    }

    // Registering the event sourcing defaults by hand must yield the same decoration as EventSourcingConfigurer does,
    // since those defaults are what register this enhancer.
    @Test
    void decoratesTheEventStorageEngineWhenTheEventSourcingDefaultsAreRegisteredManually() {
        ApplicationConfigurer configurer = MessagingConfigurer.create();
        configurer.componentRegistry(cr -> cr.registerEnhancer(new EventSourcingConfigurationDefaults())
                                             .registerComponent(SnapshotStore.class,
                                                                c -> new InMemorySnapshotStore()));

        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(EventStorageEngine.class))
                .isInstanceOf(SnapshotCapableEventStorageEngine.class);
    }

    // The supported opt-out for a storage topology composing snapshot sourcing itself.
    @Test
    void leavesTheEventStorageEngineUndecoratedWhenDisabled() {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        InMemoryEventStorageEngine composedEngine = new InMemoryEventStorageEngine();
        configurer.componentRegistry(
                cr -> cr.registerComponent(SnapshotStore.class, c -> new InMemorySnapshotStore())
                        .registerComponent(EventStorageEngine.class, c -> composedEngine)
                        .disableEnhancer(SnapshotSourcingConfigurationEnhancer.class)
        );

        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(EventStorageEngine.class)).isSameAs(composedEngine);
    }

    // A storage topology switches this off from its own enhancer, which is the cross-repo opt-out. That only works while
    // this enhancer has not run yet, so the disabling enhancer has to be ordered before it.
    @Test
    void leavesTheEventStorageEngineUndecoratedWhenDisabledByAnEarlierEnhancer() {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        InMemoryEventStorageEngine composedEngine = new InMemoryEventStorageEngine();
        configurer.componentRegistry(
                cr -> cr.registerComponent(SnapshotStore.class, config -> new InMemorySnapshotStore())
                        .registerComponent(EventStorageEngine.class, config -> composedEngine)
                        .registerEnhancer(new ComposesSnapshotSourcingItself())
        );

        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(EventStorageEngine.class)).isSameAs(composedEngine);
    }

    // A module registry receives the parent's decorator definitions by copy, so the snapshot decorator is applied a
    // second time to a module registering its own engine and snapshot store. The redundant decoration is not observable
    // through snapshot loads, since the outer decoration rewrites the condition to an absolute one before delegating and
    // the inner one then passes it through, so the nesting itself is what has to be asserted.
    @Test
    void decoratesAModulesOwnEventStorageEngineExactlyOnce() {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();
        configurer.componentRegistry(parentRegistry -> parentRegistry.registerModule(
                new EventSourcingModule("snapshot-module").componentRegistry(
                        moduleRegistry -> moduleRegistry
                                .registerComponent(SnapshotStore.class, config -> new InMemorySnapshotStore())
                                .registerComponent(EventStorageEngine.class,
                                                   config -> new InMemoryEventStorageEngine())
                )
        ));

        Configuration moduleConfig = configurer.build().getModuleConfiguration("snapshot-module").orElseThrow();

        EventStorageEngine engine = moduleConfig.getComponent(EventStorageEngine.class);
        assertThat(engine).isInstanceOf(SnapshotCapableEventStorageEngine.class);
        assertThat(engine).extracting("delegate").isNotInstanceOf(SnapshotCapableEventStorageEngine.class);
    }

    @Test
    void leavesTheEventStorageEngineUndecoratedWhenNoSnapshotStoreIsPresent() {
        ApplicationConfigurer configurer = EventSourcingConfigurer.create();

        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(EventStorageEngine.class))
                .isNotInstanceOf(SnapshotCapableEventStorageEngine.class);
    }

    private static class EventSourcingModule extends BaseModule<EventSourcingModule> {

        private EventSourcingModule(String name) {
            super(name);
        }
    }

    // Stands in for a storage topology composing snapshot sourcing per shard, tenant, or region. Ordered before the
    // enhancer it disables, since a disable is only recorded while that enhancer has not run yet.
    private static class ComposesSnapshotSourcingItself implements ConfigurationEnhancer {

        @Override
        public void enhance(@NonNull ComponentRegistry registry) {
            registry.disableEnhancer(SnapshotSourcingConfigurationEnhancer.class);
        }

        @Override
        public int order() {
            return SnapshotSourcingConfigurationEnhancer.ENHANCER_ORDER - 1;
        }
    }
}
