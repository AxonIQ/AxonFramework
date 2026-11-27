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

package org.axonframework.eventsourcing.snapshot.api;

import org.axonframework.messaging.eventhandling.EventMessage;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Defines a policy for determining when an event-sourced entity should be snapshotted.
 * <p>
 * A {@code SnapshotPolicy} encapsulates the logic that decides whether a snapshot
 * is required based on events seen and metrics collected during sourcing.
 * <p>
 * A {@code SnapshotPolicy} can trigger snapshotting in two ways:
 * <ul>
 *     <li>During sourcing, by inspecting each event through {@link #shouldSnapshot(EventMessage)}</li>
 *     <li>After sourcing completes, by evaluating the {@link EvolutionResult} through
 *         {@link #shouldSnapshot(EvolutionResult)}</li>
 * </ul>
 * <p>
 * Policies are <strong>immutable and thread-safe</strong>, so a single instance
 * can be shared across multiple entities and asynchronous operations.
 * <p>
 * Policies can be composed using {@link #or(SnapshotPolicy)} to combine multiple conditions.
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 * SnapshotPolicy policy = SnapshotPolicy.afterEvents(100)
 *                                       .or(SnapshotPolicy.whenSourcingTimeExceeds(Duration.ofMillis(50)));
 * }</pre>
 *
 * @author John Hendrikx
 * @since 5.1.0
 */
public interface SnapshotPolicy {

    /**
     * Creates a policy that triggers a snapshot once at least the specified number
     * of events have been applied since the last snapshot.
     *
     * @param eventCount the minimum number of events after which a snapshot should be made, cannot be negative
     * @return a snapshot policy based on event count, never {@code null}
     * @throws IllegalArgumentException if {@code eventCount} is a negative number
     */
    static SnapshotPolicy afterEvents(int eventCount) {
        if (eventCount < 0) {
            throw new IllegalArgumentException("The eventCount cannot be negative: " + eventCount);
        }

        return result -> result.eventsApplied() >= eventCount;
    }

    /**
     * Creates a policy that triggers a snapshot if the sourcing time of the entity
     * exceeds the specified duration.
     *
     * @param duration the maximum sourcing duration before a snapshot is triggered, cannot be {@code null}
     * @return a snapshot policy based on sourcing time, never {@code null}
     * @throws NullPointerException if {@code duration} is {@code null}
     */
    static SnapshotPolicy whenSourcingTimeExceeds(Duration duration) {
        Objects.requireNonNull(duration, "duration");

        return result -> result.sourcingTime().compareTo(duration) > 0;
    }

    /**
     * Creates a policy that triggers a snapshot when the predicate matches
     * an event encountered during sourcing.
     *
     * @param predicate an event message predicate, cannot be {@code null}
     * @return a snapshot policy that triggers when the predicate matches, never {@code null}
     * @throws NullPointerException when {@code predicate} is {@code null}
     */
    static SnapshotPolicy whenEventMatches(Predicate<EventMessage> predicate) {
        Objects.requireNonNull(predicate, "The predicate parameter must not be null.");

        return new SnapshotPolicy() {
            @Override
            public boolean shouldSnapshot(EvolutionResult evolutionResult) {
                return false;
            }

            @Override
            public boolean shouldSnapshot(EventMessage event) {
                return predicate.test(event);
            }
        };
    }

    /**
     * Allows triggering a snapshot when sourcing completes, based on the final evolution result.
     *
     * @param evolutionResult information about the sourcing process to base the decision on, cannot be {@code null}
     * @return {@code true} if a snapshot should be made, {@code false} otherwise
     * @implNote implementations should be thread-safe and side-effect free
     */
    boolean shouldSnapshot(EvolutionResult evolutionResult);

    /**
     * Allows triggering a snapshot when sourcing completes, based on an event seen during sourcing.
     *
     * @param event the event seen, never {@code null}
     * @return {@code true} if a snapshot should be created when sourcing completes, otherwise {@code false}
     * @implNote implementations should be thread-safe and side-effect free
     */
    default boolean shouldSnapshot(EventMessage event) {
        return false;
    }

    /**
     * Composes this policy with another policy using logical OR.
     * <p>
     * The resulting policy triggers a snapshot if either this policy or the
     * other policy requires it.
     *
     * @param other another snapshot policy, cannot be {@code null}
     * @return a new {@code SnapshotPolicy} representing the logical OR of this and the other, never {@code null}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    default SnapshotPolicy or(SnapshotPolicy other) {
        Objects.requireNonNull(other, "The other parameter must not be null.");

        return new SnapshotPolicy() {
            @Override
            public boolean shouldSnapshot(EventMessage event) {
                return SnapshotPolicy.this.shouldSnapshot(event) || other.shouldSnapshot(event);
            }

            @Override
            public boolean shouldSnapshot(EvolutionResult evolutionResult) {
                return SnapshotPolicy.this.shouldSnapshot(evolutionResult) || other.shouldSnapshot(evolutionResult);
            }
        };
    }
}
