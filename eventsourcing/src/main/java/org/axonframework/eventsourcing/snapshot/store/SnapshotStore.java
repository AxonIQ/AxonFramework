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

package org.axonframework.eventsourcing.snapshot.store;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Represents storage for snapshots of event-sourced entities.
 * <p>
 * A {@code SnapshotStore} is responsible for persisting and retrieving snapshots
 * to optimize the sourcing of entities, avoiding the need to apply all events from the beginning.
 * <p>
 * Implementations are expected to be thread-safe and fully asynchronous, returning
 * {@link CompletableFuture} for all operations.
 *
 * @author John Hendrikx
 * @since 5.1.0
 */
@Internal
public interface SnapshotStore extends DescribableComponent {

    /**
     * Persists a snapshot under the given name and identifier. This replaces any previous
     * snapshot(s) with the same name and identifier.
     * <p>
     * This method is asynchronous and returns a {@link CompletableFuture} that
     * completes when the snapshot has been durably stored.
     *
     * @param qualifiedName the name of the snapshot to persist, cannot be {@code null}
     * @param identifier the identifier of the snapshot to persist, cannot be {@code null}
     * @param snapshot the snapshot to persist, cannot be {@code null}
     * @param context the {@link ProcessingContext} active while storing, used by decorators (e.g. tracing) to correlate
     *                the operation with the surrounding unit of work; may be {@code null}
     * @return a {@link CompletableFuture} that completes when the snapshot has been stored
     * @throws NullPointerException if any non-nullable argument is {@code null}
     */
    CompletableFuture<Void> store(QualifiedName qualifiedName, Object identifier, Snapshot snapshot,
                                  @Nullable ProcessingContext context);

    /**
     * Loads the latest snapshot for a given name and identifier.
     * <p>
     * The returned snapshot may have an older or newer version relative to what is expected.
     * The caller is responsible for handling version compatibility or ignoring unusable snapshots.
     * <p>
     * This method is asynchronous and returns a {@link CompletableFuture} that
     * completes with the snapshot if one exists, or {@code null} if no snapshot
     * is available.
     *
     * @param qualifiedName the name of the snapshot, cannot be {@code null}
     * @param identifier the identifier of the snapshot, cannot be {@code null}
     * @param context the {@link ProcessingContext} active while loading, used by decorators (e.g. tracing) to correlate
     *                the operation with the surrounding unit of work; may be {@code null}
     * @return a {@link CompletableFuture} containing the snapshot, or containing {@code null} if no matching snapshot exists
     * @throws NullPointerException if any non-nullable argument is {@code null}
     */
    CompletableFuture<@Nullable Snapshot> load(QualifiedName qualifiedName, Object identifier,
                                               @Nullable ProcessingContext context);
}
