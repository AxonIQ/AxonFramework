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

package org.axonframework.eventsourcing.eventstore;

import org.axonframework.eventsourcing.snapshot.api.SnapshotCandidate;
import org.axonframework.messaging.core.QualifiedName;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Defines how a sourcing operation should construct the message stream when reading events
 * from an event store.
 * <p>
 * There are two supported forms:
 * <ul>
 *     <li>{@link Absolute} - start from a concrete position in the event stream</li>
 *     <li>{@link Snapshot} - include a snapshot (if available), followed by subsequent events</li>
 * </ul>
 *
 * <h2>Merging</h2>
 * Sourcing strategies can be merged to produce a single effective strategy used for sourcing.
 * The merge rules are deterministic:
 * <ul>
 *     <li>Merging two {@link Absolute} strategies results in the minimum of both positions</li>
 *     <li>Merging an {@code Absolute} with a {@code Snapshot} always results in the {@code Snapshot}</li>
 *     <li>Merging two {@code Snapshot} instances is not supported and results in an exception</li>
 * </ul>
 * <h2>Snapshot semantics</h2>
 * The {@link Snapshot} strategy provides initial state, replacing the initial part of the event stream if
 * initial state was available. It
 * is identified by:
 * <ul>
 *     <li>One or more {@link SnapshotCandidate candidates}, each a name together with the snapshot versions acceptable
 *     under it</li>
 *     <li>An optional aggregate-based candidate, describing an aggregate-based snapshot of the same entity, such as
 *     one taken by Axon Framework 4</li>
 *     <li>An identifier (typically an aggregate or entity id)</li>
 *     <li>An optional maximum {@link Position}, limiting which snapshots are acceptable</li>
 * </ul>
 *
 * When a snapshot is used, the event store may return:
 * <ul>
 *     <li>The latest snapshot matching the identity whose position is at or before the specified maximum position</li>
 *     <li>All subsequent events from the snapshot's position onward</li>
 * </ul>
 *
 * If no snapshot is available, the event store falls back to sourcing events only.
 *
 * @author John Hendrikx
 * @since 5.1.1
 */
public sealed interface SourcingStrategy {

    /**
     * Merges this sourcing strategy with another sourcing strategy to produce a single
     * effective strategy for event sourcing.
     * <p>
     * The merge operation is deterministic and defined as follows:
     * <ul>
     *     <li>Merging two {@link Absolute} strategies results in an {@code Absolute}
     *     whose position is the minimum of both positions</li>
     *     <li>Merging an {@link Absolute} with a {@link Snapshot} results in the {@code Snapshot}</li>
     *     <li>Merging two {@link Snapshot} instances is not supported and results in an
     *     {@link UnsupportedOperationException}</li>
     * </ul>
     *
     * @param other the other sourcing strategy to merge with this one, cannot be {@code null}
     * @return the merged sourcing strategy
     * @throws UnsupportedOperationException if both strategies are {@link Snapshot} instances
     * @throws NullPointerException if any argument is {@code null}
     */
    SourcingStrategy merge(SourcingStrategy other);

    /**
     * Absolute start position in the event stream.
     *
     * @param position the position, cannot be {@code null}
     */
    record Absolute(Position position) implements SourcingStrategy {

        /**
         * Constructs a new instance.
         *
         * @throws NullPointerException if any argument is {@code null}
         */
        public Absolute {
            Objects.requireNonNull(position, "The position parameter cannot be null.");
        }

        @Override
        public SourcingStrategy merge(SourcingStrategy other) {
            return switch (other) {
                case Absolute(Position p) -> new Absolute(p.min(position));
                case Snapshot s -> s;  // snapshot wins, as the more optimal strategy
            };
        }
    }

    /**
     * Snapshot-based sourcing strategy.
     * <p>
     * This strategy provides an initial state (snapshot) if available, succeeded by an
     * ordered event stream from the position of the snapshot.
     * <p>
     * The snapshot is looked up under the given {@link SnapshotCandidate candidates}, in order: the first candidate
     * under whose name a snapshot of an acceptable version is stored wins. The first candidate is normally the name and
     * version the entity is currently known by; later ones name what it was previously known as, so a snapshot taken
     * before a rename or a version change can still be found.
     * <p>
     * When none of the candidates yields a snapshot, the optional aggregate-based candidate is tried last. It describes
     * an aggregate-based snapshot, such as one taken by Axon Framework 4, which is stored under the identifier of its
     * aggregate rather than under a name: its {@link SnapshotCandidate#qualifiedName() name} is the payload type of
     * that snapshot, and its {@link SnapshotCandidate#acceptableVersions() versions} are the acceptable revisions of
     * that payload type, where a snapshot without a revision has version {@code 0.0.0}. Only stores that can hold
     * aggregate-based snapshots look it up; it is ignored by all others.
     *
     * @param candidates the candidates to look the snapshot up under, in order of preference, cannot be {@code null}
     *                   or empty, and cannot contain {@code null}
     * @param aggregateBasedCandidate the aggregate-based snapshot to look up when no candidate yields a snapshot, can
     *                                be {@code null} when there is none to look up
     * @param identifier the identifier of the snapshotted entity, cannot be {@code null}
     * @param maximumPosition the maximum position of the snapshot to return, can be {@code null}
     */
    record Snapshot(
        List<SnapshotCandidate> candidates,
        @Nullable SnapshotCandidate aggregateBasedCandidate,
        Object identifier,
        @Nullable Position maximumPosition
    ) implements SourcingStrategy {

        /**
         * Constructs a new instance.
         *
         * @throws NullPointerException     if {@code candidates} or {@code identifier} is {@code null}, or
         *                                  {@code candidates} contains {@code null}
         * @throws IllegalArgumentException if {@code candidates} is empty
         */
        public Snapshot {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "The candidates parameter cannot be null."));
            Objects.requireNonNull(identifier, "The identifier parameter cannot be null.");

            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("The candidates parameter cannot be empty.");
            }
        }

        /**
         * Constructs a new instance without an aggregate-based candidate.
         *
         * @param candidates      the candidates to look the snapshot up under, in order of preference, cannot be
         *                        {@code null} or empty, and cannot contain {@code null}
         * @param identifier      the identifier of the snapshotted entity, cannot be {@code null}
         * @param maximumPosition the maximum position of the snapshot to return, can be {@code null}
         * @throws NullPointerException     if {@code candidates} or {@code identifier} is {@code null}, or
         *                                  {@code candidates} contains {@code null}
         * @throws IllegalArgumentException if {@code candidates} is empty
         */
        public Snapshot(List<SnapshotCandidate> candidates, Object identifier, @Nullable Position maximumPosition) {
            this(candidates, null, identifier, maximumPosition);
        }

        /*
         * Temporary: kept only so Axoniq Framework keeps compiling until it constructs this strategy from candidates.
         * Having no version to offer, it accepts a snapshot of any version. Remove together with the other bridges
         * for that migration.
         */
        /**
         * Constructs a new instance looking the snapshot up under a single name, accepting any version.
         *
         * @param qualifiedName   the name to look the snapshot up under, cannot be {@code null}
         * @param identifier      the identifier of the snapshotted entity, cannot be {@code null}
         * @param maximumPosition the maximum position of the snapshot to return, can be {@code null}
         * @throws NullPointerException if {@code qualifiedName} or {@code identifier} is {@code null}
         * @deprecated use {@link #Snapshot(List, Object, Position)}, stating the versions that can be read
         */
        @Deprecated(since = "5.5.0", forRemoval = true)
        public Snapshot(QualifiedName qualifiedName, Object identifier, @Nullable Position maximumPosition) {
            this(
                List.of(new SnapshotCandidate(
                    Objects.requireNonNull(qualifiedName, "The qualifiedName parameter cannot be null."),
                    Set.of()
                )),
                identifier,
                maximumPosition
            );
        }

        /*
         * Temporary: kept only so Axoniq Framework keeps compiling until it reads the candidates instead. Remove
         * together with the other bridges for that migration.
         */
        /**
         * Returns the name of the first candidate, which is normally the name the entity is currently known by.
         *
         * @return the name of the first candidate, never {@code null}
         * @deprecated use {@link #candidates()}, which also carries the names a snapshot may be stored under after a
         * rename, and the versions acceptable under each
         */
        @Deprecated(since = "5.5.0", forRemoval = true)
        public QualifiedName qualifiedName() {
            return candidates.getFirst().qualifiedName();
        }

        @Override
        public SourcingStrategy merge(SourcingStrategy other) {
            if (other instanceof Snapshot) {
                throw new UnsupportedOperationException("Cannot combine two snapshot sourcing strategies: " + this + " + " + other);
            }

            return this;
        }
    }
}