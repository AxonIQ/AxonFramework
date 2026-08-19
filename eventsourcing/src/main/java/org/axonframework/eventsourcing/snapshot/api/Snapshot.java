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

import org.axonframework.eventsourcing.eventstore.Position;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a snapshot of an entity at a specific position in the event stream.
 * <p>
 * A snapshot captures the state of the entity at a given point, allowing
 * the entity to be sourced from this state instead of from scratch.
 *
 * @param position the position in the event stream corresponding to this snapshot, never {@code null}
 * @param version the entity's version for this snapshot, never {@code null}
 * @param payload the payload of the snapshot, never {@code null}
 * @param timestamp the timestamp of the snapshot, never {@code null}
 * @param metadata the metadata of the snapshot, never {@code null}
 * @author John Hendrikx
 * @since 5.1.0
 */
public record Snapshot(
    Position position,
    String version,
    Object payload,
    Instant timestamp,
    Map<String, String> metadata
) {

    /**
     * Constructs a new instance.
     *
     * @throws NullPointerException when any argument is {@code null}
     */
    public Snapshot {
        Objects.requireNonNull(position, "The position parameter must not be null.");
        Objects.requireNonNull(version, "The version parameter must not be null.");
        Objects.requireNonNull(payload, "The payload parameter must not be null.");
        Objects.requireNonNull(timestamp, "The timestamp parameter must not be null.");
        Objects.requireNonNull(metadata, "The metadata parameter must not be null.");
    }

    /**
     * Creates a copy of this snapshot with the given payload.
     *
     * @param payload the new payload, cannot be {@code null}
     * @return a new snapshot, never {@code null}
     */
    public Snapshot payload(Object payload) {
        return new Snapshot(position, version, payload, timestamp, metadata);
    }
}
