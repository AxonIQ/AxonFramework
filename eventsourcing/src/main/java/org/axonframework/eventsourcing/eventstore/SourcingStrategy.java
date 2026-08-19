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

import org.axonframework.messaging.core.QualifiedName;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

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
 *     <li>A {@link QualifiedName}, defining the snapshot type</li>
 *     <li>An identifier (typically an aggregate or entity id)</li>
 *     <li>An optional maximum {@link Position}, limiting which snapshots are acceptable</li>
 * </ul>
 *
 * When a snapshot is used, the event store may return:
 * <ul>
 *     <li>The latest snapshot matching the identity whose position is at or before the specified maximum position</li>
 *     <li>All subsequent events from the snapshot’s position onward</li>
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
     *
     * @param qualifiedName the {@link QualifiedName} defining the snapshot type, cannot be {@code null}
     * @param identifier the identifier of the snapshotted entity, cannot be {@code null}
     * @param maximumPosition the maximum position of the snapshot to return, can be {@code null}
     */
    record Snapshot(QualifiedName qualifiedName, Object identifier, @Nullable Position maximumPosition) implements SourcingStrategy {

        /**
         * Constructs a new instance.
         *
         * @throws NullPointerException if {@code qualifiedName} or {@code identifier} is {@code null}
         */
        public Snapshot {
            Objects.requireNonNull(qualifiedName, "The qualifiedName parameter cannot be null.");
            Objects.requireNonNull(identifier, "The identifier parameter cannot be null.");
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