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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
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
 * When constructed with a {@link Converter}, payloads are copied on both {@link #store} and {@link #load} by
 * serializing and deserializing them. That isolates the stored snapshot from later mutations of a mutable entity,
 * matching the isolation production stores get from durable serialization.
 * <p>
 * All operations return {@link CompletableFuture} to conform with the {@link SnapshotStore}
 * asynchronous API, but they complete immediately since storage is in-memory.
 *
 * @author John Hendrikx
 * @since 5.1.0
 */
public class InMemorySnapshotStore implements SnapshotStore {

    private final Map<QualifiedName, Map<Object, Snapshot>> entitiesByIdentifierByName = new ConcurrentHashMap<>();
    private final @Nullable Converter converter;

    /**
     * Instantiates an in-memory snapshot store that retains snapshot payloads by reference.
     * <p>
     * Prefer {@link #InMemorySnapshotStore(Converter)}, which copies payloads so that later mutations of a mutable
     * entity do not change a snapshot that has already been stored.
     *
     * @deprecated in favor of {@link #InMemorySnapshotStore(Converter)}
     */
    @Deprecated(since = "5.4.0")
    public InMemorySnapshotStore() {
        this.converter = null;
    }

    /**
     * Instantiates an in-memory snapshot store that copies snapshot payloads through the given {@code converter}.
     *
     * @param converter the {@link Converter} used to copy snapshot payloads on store and load
     * @since 5.4.0
     */
    public InMemorySnapshotStore(Converter converter) {
        this.converter = Objects.requireNonNull(converter, "The converter parameter must not be null.");
    }

    @Override
    public CompletableFuture<Void> store(QualifiedName qualifiedName, Object identifier, Snapshot snapshot,
                                         @Nullable ProcessingContext context) {
        Objects.requireNonNull(qualifiedName, "The qualifiedName parameter must not be null.");
        Objects.requireNonNull(identifier, "The identifier parameter must not be null.");
        Objects.requireNonNull(snapshot, "The snapshot parameter must not be null.");

        entitiesByIdentifierByName
            .computeIfAbsent(qualifiedName, k -> new ConcurrentHashMap<>())
            .put(identifier, copySnapshot(snapshot));

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<@Nullable Snapshot> load(QualifiedName qualifiedName, Object identifier,
                                                      @Nullable ProcessingContext context) {
        Objects.requireNonNull(qualifiedName, "The qualifiedName parameter must not be null.");
        Objects.requireNonNull(identifier, "The identifier parameter must not be null.");

        Map<Object, Snapshot> entitiesByIdentifier = entitiesByIdentifierByName.get(qualifiedName);
        Snapshot stored = entitiesByIdentifier == null ? null : entitiesByIdentifier.get(identifier);

        return CompletableFuture.completedFuture(stored == null ? null : copySnapshot(stored));
    }

    private Snapshot copySnapshot(Snapshot snapshot) {
        if (converter == null) {
            return snapshot;
        }
        return new Snapshot(
            snapshot.position(),
            snapshot.version(),
            copyPayload(snapshot.payload()),
            snapshot.timestamp(),
            Map.copyOf(snapshot.metadata())
        );
    }

    private Object copyPayload(Object payload) {
        if (payload instanceof byte[] bytes) {
            return bytes.clone();
        }
        Class<?> payloadType = payload.getClass();
        byte[] serialized = converter.convert(payload, byte[].class);
        if (serialized == null) {
            throw new ConversionException(
                "Converter returned a null serialization of snapshot payload of type " + payloadType.getName()
            );
        }
        Object copy = converter.convert(serialized, payloadType);
        if (copy == null) {
            throw new ConversionException(
                "Converter returned a null copy of snapshot payload of type " + payloadType.getName()
            );
        }
        if (copy == payload) {
            throw new ConversionException(
                "Converter did not produce an independent copy of snapshot payload of type " + payloadType.getName()
            );
        }
        return copy;
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("converter", converter);
    }
}
