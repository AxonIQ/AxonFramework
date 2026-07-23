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

package org.axonframework.eventsourcing.snapshot.inmemory;

import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory implementation of {@link SnapshotStore} for testing or lightweight scenarios.
 * <p>
 * This store keeps snapshots in memory only and does not persist them to any durable storage.
 * It is thread-safe.
 * <p>
 * Snapshots are stored by their qualified name and identifier.
 * <p>
 * All operations return {@link CompletableFuture} to conform with the {@link SnapshotStore}
 * asynchronous API, but they complete immediately since storage is in-memory.</p>
 *
 * @author John Hendrikx
 * @since 5.1.0
 */
public class InMemorySnapshotStore implements SnapshotStore {
    private final Map<QualifiedName, Map<Object, Snapshot>> entitiesByIdentifierByName = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> store(QualifiedName qualifiedName, Object identifier, Snapshot snapshot,
                                         @Nullable ProcessingContext context) {
        Objects.requireNonNull(qualifiedName, "The qualifiedName parameter must not be null.");
        Objects.requireNonNull(identifier, "The identifier parameter must not be null.");
        Objects.requireNonNull(snapshot, "The snapshot parameter must not be null.");

        entitiesByIdentifierByName
            .computeIfAbsent(qualifiedName, k -> new ConcurrentHashMap<>())
            .put(identifier, snapshot);

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<@Nullable Snapshot> load(QualifiedName qualifiedName, Object identifier,
                                                      @Nullable ProcessingContext context) {
        Objects.requireNonNull(qualifiedName, "The qualifiedName parameter must not be null.");
        Objects.requireNonNull(identifier, "The identifier parameter must not be null.");

        Map<Object, Snapshot> entitiesByIdentifier = entitiesByIdentifierByName.get(qualifiedName);

        return CompletableFuture.completedFuture(entitiesByIdentifier == null ? null : entitiesByIdentifier.get(identifier));
    }
}
