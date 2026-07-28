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

import org.axonframework.common.annotation.RegistrationScope;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SnapshotCapableEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SourcingStrategy;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;

/**
 * A {@link ConfigurationEnhancer} making the {@link EventStorageEngine} support the
 * {@link SourcingStrategy.Snapshot snapshot sourcing strategy}, by decorating it with the configured
 * {@link SnapshotStore} through {@link SnapshotCapableEventStorageEngine#decorate(EventStorageEngine, SnapshotStore)}.
 * <p>
 * The engine is left as is when no {@code SnapshotStore} is configured, and when it is the configured
 * {@code SnapshotStore} itself. The latter resolves the snapshot within its own {@code source} call, in a single round
 * trip, which decorating would undo.
 * <p>
 * Registered by {@link EventSourcingConfigurationDefaults}, so applications registering those defaults manually receive
 * it as well. It is deliberately not contributed through the {@link java.util.ServiceLoader}, unlike its sibling
 * enhancers, to keep that single registration channel. Registered for the {@link RegistrationScope.Scope#CURRENT
 * current} registry only, since the defaults registering it are copied into every module registry and would otherwise
 * register it there a second time.
 * <p>
 * A storage topology composing snapshot sourcing itself disables this enhancer, taking over that responsibility:
 * <pre>{@code
 * configurer.componentRegistry(
 *         registry -> registry.disableEnhancer(SnapshotSourcingConfigurationEnhancer.class)
 * );
 * }</pre>
 * An engine routing {@code source} to one engine per shard, tenant, or region is the case this exists for: the shard is
 * only known once a message is inspected, so decorating the routing engine would resolve snapshots before then, and the
 * engines it routes to would never receive the snapshot sourcing strategy. Such a topology composes each of its engines
 * with that engine's own snapshot store instead, through the same
 * {@link SnapshotCapableEventStorageEngine#decorate(EventStorageEngine, SnapshotStore) decorate} operation.
 * <p>
 * Disabling this enhancer without composing snapshot sourcing elsewhere leaves snapshots being written while never
 * being read: sourcing then replays an entity's full event history.
 *
 * @author Laura Devriendt
 * @since 5.3.0
 */
@RegistrationScope(scope = RegistrationScope.Scope.CURRENT)
public class SnapshotSourcingConfigurationEnhancer implements ConfigurationEnhancer {

    /**
     * The order of {@code this} enhancer compared to others, equal to 10 positions after
     * {@link EventSourcingConfigurationDefaults} (thus,
     * {@link EventSourcingConfigurationDefaults#ENHANCER_ORDER} + 10).
     * <p>
     * What this order governs is the window in which {@code this} enhancer can still be disabled: a disable is only
     * recorded while the enhancer has not been invoked yet, and is otherwise reported as having no effect. Running late
     * therefore leaves every earlier-ordered enhancer free to take over snapshot composition.
     */
    public static final int ENHANCER_ORDER = EventSourcingConfigurationDefaults.ENHANCER_ORDER + 10;

    @Override
    public int order() {
        return ENHANCER_ORDER;
    }

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerDecorator(
                EventStorageEngine.class,
                SnapshotCapableEventStorageEngine.DECORATION_ORDER,
                (config, name, engine) -> config
                        .getOptionalComponent(SnapshotStore.class)
                        .map(snapshotStore -> SnapshotCapableEventStorageEngine.decorate(engine, snapshotStore))
                        .orElse(engine)
        );
    }
}
