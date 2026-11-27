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
import org.axonframework.eventsourcing.snapshot.api.SnapshotCandidate;
import org.axonframework.eventsourcing.snapshot.api.SnapshotMatch;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
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
     * Loads the best acceptable snapshot for the given identifier, trying the given candidates in order, and the given
     * aggregate-based candidate after them.
     * <p>
     * The candidates are tried as described for {@link #load(List, Object, ProcessingContext)}. Only when none of them
     * yields an acceptable snapshot is the {@code aggregateBasedCandidate} tried, if given: it describes an
     * aggregate-based snapshot, such as one taken by Axon Framework 4, which is stored under the identifier alone. Such
     * a snapshot is acceptable only if its payload type equals the aggregate-based candidate's
     * {@link SnapshotCandidate#qualifiedName() name}, and its revision is among the aggregate-based candidate's
     * {@link SnapshotCandidate#acceptableVersions() acceptable versions}, where a snapshot without a revision has
     * version {@code 0.0.0}. When found, it is returned under the aggregate-based candidate's name, with a position
     * following the same convention as any other snapshot.
     * <p>
     * A store that cannot hold aggregate-based snapshots ignores the {@code aggregateBasedCandidate}; this is what the
     * default implementation does.
     *
     * @param candidates      the candidates to try, in order of preference, cannot be {@code null} or empty
     * @param aggregateBasedCandidate the aggregate-based snapshot to try when no candidate yields an acceptable
     *                                snapshot, can be {@code null} when there is none to try
     * @param identifier      the identifier of the snapshot, cannot be {@code null}
     * @param context         the {@link ProcessingContext} active while loading, used by decorators (e.g. tracing) to
     *                        correlate the operation with the surrounding unit of work; may be {@code null}
     * @return a {@link CompletableFuture} containing the snapshot found and the name it was found under, or containing
     * {@code null} if no candidate yields an acceptable snapshot
     * @throws NullPointerException     if any non-nullable argument is {@code null}, or {@code candidates} contains
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code candidates} is empty
     */
    default CompletableFuture<@Nullable SnapshotMatch> load(List<SnapshotCandidate> candidates,
                                                            @Nullable SnapshotCandidate aggregateBasedCandidate,
                                                            Object identifier,
                                                            @Nullable ProcessingContext context) {
        return load(candidates, identifier, context);
    }

    /*
     * Temporary: a default only so Axoniq Framework's stores keep compiling, and keep working, while they implement
     * just the deprecated single-name load below. It makes one such load per candidate, so it only sees the most
     * recent snapshot under each name. Once they have migrated, this becomes abstract again and the single-name load
     * is removed, together with the other bridges for that migration.
     */
    /**
     * Loads the best acceptable snapshot for the given identifier, trying the given candidates in order.
     * <p>
     * Candidates are tried strictly in the given order, and the first candidate under whose name an acceptable
     * snapshot is stored wins: a later candidate is never preferred over an earlier one. Of the snapshots stored under
     * a candidate's name, only those whose {@link Snapshot#version() version} is among the candidate's
     * {@link SnapshotCandidate#acceptableVersions() acceptable versions} are considered, and of those the most recently
     * stored one is returned. Versions are matched for membership only; a store does not order them, so the result is
     * the most recent acceptable snapshot rather than the one with the highest version.
     * <p>
     * This method is asynchronous and returns a {@link CompletableFuture} that completes with the snapshot found,
     * together with the name it was found under, or {@code null} if no candidate yields an acceptable snapshot.
     *
     * @param candidates the candidates to try, in order of preference, cannot be {@code null} or empty
     * @param identifier the identifier of the snapshot, cannot be {@code null}
     * @param context    the {@link ProcessingContext} active while loading, used by decorators (e.g. tracing) to
     *                   correlate the operation with the surrounding unit of work; may be {@code null}
     * @return a {@link CompletableFuture} containing the snapshot found and the name it was found under, or containing
     * {@code null} if no candidate yields an acceptable snapshot
     * @throws NullPointerException     if any non-nullable argument is {@code null}, or {@code candidates} contains
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code candidates} is empty
     */
    default CompletableFuture<@Nullable SnapshotMatch> load(List<SnapshotCandidate> candidates, Object identifier,
                                                            @Nullable ProcessingContext context) {
        List<SnapshotCandidate> candidateList =
            List.copyOf(Objects.requireNonNull(candidates, "The candidates parameter must not be null."));
        Objects.requireNonNull(identifier, "The identifier parameter must not be null.");

        if (candidateList.isEmpty()) {
            throw new IllegalArgumentException("The candidates parameter must not be empty.");
        }

        return loadFirstAcceptable(candidateList, 0, identifier, context);
    }

    /*
     * Temporary, part of the default above.
     */
    private CompletableFuture<@Nullable SnapshotMatch> loadFirstAcceptable(List<SnapshotCandidate> candidates,
                                                                           int index,
                                                                           Object identifier,
                                                                           @Nullable ProcessingContext context) {
        if (index == candidates.size()) {
            return CompletableFuture.completedFuture(null);
        }

        SnapshotCandidate candidate = candidates.get(index);

        return load(candidate.qualifiedName(), identifier, context).thenCompose(
            snapshot -> snapshot != null && candidate.accepts(snapshot.version())
                ? CompletableFuture.completedFuture(new SnapshotMatch(candidate.qualifiedName(), snapshot))
                : loadFirstAcceptable(candidates, index + 1, identifier, context)
        );
    }

    /*
     * Temporary: kept only so Axoniq Framework keeps compiling until its stores implement the candidate load above.
     * Remove together with the other bridges for that migration.
     */
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
     * @deprecated use {@link #load(List, Object, ProcessingContext)}, which only returns a snapshot of a version that
     * can be read
     */
    @Deprecated(since = "5.5.0", forRemoval = true)
    CompletableFuture<@Nullable Snapshot> load(QualifiedName qualifiedName, Object identifier,
                                               @Nullable ProcessingContext context);
}
