package org.axonframework.eventsourcing.eventstore;

import org.axonframework.messaging.core.MessageType;

import java.util.Objects;

/**
 * Defines how a read operation should construct the message stream when sourcing events
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
 * A {@link Snapshot} represents an atomic sourcing instruction that provides initial state and
 * may replace part of the event stream. It is identified by:
 * <ul>
 *     <li>A message type ({@link MessageType}), defining the snapshot type and maximum version</li>
 *     <li>An identifier (typically an aggregate or entity id)</li>
 * </ul>
 *
 * When a snapshot is used, the event store may return:
 * <ul>
 *     <li>The latest snapshot matching the identity whose version is less than or equal to the specified maximum version</li>
 *     <li>All subsequent events from the snapshot’s position onward</li>
 * </ul>
 *
 * If no snapshot is available, the event store falls back to sourcing events only.
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
     * <h2>Snapshot precedence</h2>
     * A {@code Snapshot} is considered an atomic and authoritative source of initial state. When present,
     * it always takes precedence over any {@code Absolute} position.
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
     * Snapshot is an atomic sourcing instruction that provides initial state and
     * cannot be merged with another snapshot.
     *
     * @param messageType the {@link MessageType} defining the snapshot type and maximum version, cannot be {@code null}
     * @param identifier the identifier of the snapshotted entity, cannot be {@code null}
     */
    record Snapshot(MessageType messageType, Object identifier) implements SourcingStrategy {

        /**
         * Constructs a new instance.
         *
         * @throws NullPointerException if any argument is {@code null}
         */
        public Snapshot {
            Objects.requireNonNull(messageType, "The messageType parameter cannot be null.");
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