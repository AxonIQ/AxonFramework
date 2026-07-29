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

package org.axonframework.examples.demo.multitenancy.shared.tenant;

import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import io.axoniq.framework.messaging.multitenancy.eventsourcing.TenantSnapshotStoreFactory;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.QualifiedName;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Reads what one tenant's own snapshot store captured for an entity, so the demo can observe where a
 * snapshot ended up and what it holds.
 * <p>
 * Using snapshots needs none of this. The framework stores and reads a snapshot in the store of the tenant
 * handling the command, without the application naming a tenant. Only this demo needs to look them up, and
 * it needs to because a snapshot is a performance optimization. An entity behaves identically whether or
 * not it was snapshotted, so no behavior reveals which tenant's store a snapshot landed in.
 * <p>
 * Get one through {@link #axonServer(AxonConfiguration, QualifiedName, Class)}, or {@link #inMemory()} for
 * a backing where every tenant shares one store.
 *
 * @param <T> the type a stored snapshot's contents are read into
 */
public interface TenantSnapshots<T> {

    /**
     * Whether each tenant has its own snapshot store, which only Axon Server gives it.
     *
     * @return {@code true} against Axon Server, {@code false} in memory
     */
    boolean hasPerTenantSnapshotStore();

    /**
     * What the given {@code tenant}'s own store captured for the given entity, or {@code null} when it holds
     * no snapshot of it.
     *
     * @param tenant     the tenant whose snapshot store to read
     * @param identifier the identifier of the entity to look for
     * @return that tenant's snapshot contents, or {@code null} when there is none
     * @throws UnsupportedOperationException when this backing has no per-tenant snapshot stores
     */
    @Nullable
    T snapshotContentsOf(TenantDescriptor tenant, String identifier);

    /**
     * Snapshot lookups over a backing that gives every tenant the same snapshot store, which is what the
     * in-memory run does. There is no per-tenant store to read, so a lookup is refused.
     *
     * @param <T> the type a stored snapshot's contents would be read into
     * @return snapshot lookups that report they are not per tenant
     */
    static <T> TenantSnapshots<T> inMemory() {
        return new TenantSnapshots<>() {
            @Override
            public boolean hasPerTenantSnapshotStore() {
                return false;
            }

            @Override
            public T snapshotContentsOf(TenantDescriptor tenant, String identifier) {
                throw new UnsupportedOperationException(
                        "In memory every tenant shares one snapshot store, so there is no per-tenant store to read");
            }
        };
    }

    /**
     * Snapshot lookups over Axon Server, where each tenant's snapshots live in that tenant's own context.
     * It reads the same per-tenant store the framework writes through, converted with the application's own
     * converter.
     *
     * @param configuration the started configuration to resolve the per-tenant stores and converter from
     * @param snapshotName  the name the entity's snapshots are stored under
     * @param contentsType  the type a stored snapshot's contents are read into
     * @param <T>           the type a stored snapshot's contents are read into
     * @return the Axon Server snapshot lookups
     */
    static <T> TenantSnapshots<T> axonServer(AxonConfiguration configuration,
                                             QualifiedName snapshotName,
                                             Class<T> contentsType) {
        Objects.requireNonNull(configuration, "The configuration must not be null");
        Objects.requireNonNull(snapshotName, "The snapshot name must not be null");
        Objects.requireNonNull(contentsType, "The contents type must not be null");
        TenantSnapshotStoreFactory snapshotStores = configuration.getComponent(TenantSnapshotStoreFactory.class);
        GeneralConverter converter = configuration.getComponent(GeneralConverter.class);
        // Bounded, so a stalled server does not hang the demo.
        Duration lookupTimeout = Duration.ofSeconds(5);
        return new TenantSnapshots<>() {
            @Override
            public boolean hasPerTenantSnapshotStore() {
                return true;
            }

            @Override
            public T snapshotContentsOf(TenantDescriptor tenant, String identifier) {
                SnapshotStore tenantStore = snapshotStores.storeFor(tenant);
                Snapshot snapshot = tenantStore.load(snapshotName, identifier, null)
                                               .orTimeout(lookupTimeout.toMillis(), TimeUnit.MILLISECONDS)
                                               .join();
                return snapshot == null ? null : converter.convert(snapshot.payload(), contentsType);
            }
        };
    }
}
