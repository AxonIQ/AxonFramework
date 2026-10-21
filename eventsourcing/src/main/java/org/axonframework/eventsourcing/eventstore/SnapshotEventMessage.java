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

import org.axonframework.common.annotation.Internal;
import org.axonframework.eventsourcing.snapshot.api.Snapshot;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.VersionedType;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Message containing a snapshot.
 * <p>
 * This message can be emitted when calling {@link EventStoreTransaction#source(SourcingCondition)}
 * when using the {@link SourcingStrategy.Snapshot snapshot sourcing strategy} and a suitable snapshot
 * was available.
 * <p>
 * The {@link #type()} of this message is the same for every snapshot, so it is never mistaken for an event type. The
 * type of the entity state it carries is available separately, as its {@link #snapshotType() snapshot type}.
 *
 * @author John Hendrikx
 * @since 5.1.0
 */
@Internal
public class SnapshotEventMessage extends GenericEventMessage {

    private static final MessageType TYPE = new MessageType(SnapshotEventMessage.class);

    private final @Nullable VersionedType snapshotType;

    /**
     * Constructs a new instance.
     * <p>
     * The {@link #snapshotType() snapshot type} of this message describes the snapshot as it was stored, combining
     * the {@link QualifiedName} it was found under with the {@link Snapshot#version() version} it carries. This is
     * what allows a snapshot taken under an older name or version to be recognized and upcast, rather than only
     * compared against the type an entity is currently declared with.
     *
     * @param qualifiedName the name the snapshot was found under, cannot be {@code null}
     * @param snapshot the snapshot, cannot be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public SnapshotEventMessage(QualifiedName qualifiedName, Snapshot snapshot) {
        super(TYPE, Objects.requireNonNull(snapshot, "The snapshot parameter cannot be null."));

        this.snapshotType = VersionedType.of(
            Objects.requireNonNull(qualifiedName, "The qualifiedName parameter cannot be null."),
            snapshot.version()
        );
    }

    /*
     * Temporary: kept only so Axoniq Framework keeps compiling until it moves to the constructor taking the name a
     * snapshot was found under. Remove together with the other bridges for that migration, which also makes
     * snapshotType() non-null.
     */
    /**
     * Constructs a new instance without a {@link #snapshotType() snapshot type}.
     *
     * @param snapshot the snapshot, cannot be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     * @deprecated use {@link #SnapshotEventMessage(QualifiedName, Snapshot)}, which describes the snapshot as it was
     * stored
     */
    @Deprecated(since = "5.5.0", forRemoval = true)
    public SnapshotEventMessage(Snapshot snapshot) {
        super(TYPE, Objects.requireNonNull(snapshot, "The snapshot parameter cannot be null."));

        this.snapshotType = null;
    }

    /**
     * Returns the type of the entity state this snapshot carries: the name it was found under, together with its
     * {@link Snapshot#version() version}.
     * <p>
     * This is the stored identity of the snapshot, which may differ from the type the entity is currently declared
     * with when the snapshot was taken under an older name or version.
     *
     * @return the snapshot type, or {@code null} if this message was constructed without one
     */
    public @Nullable VersionedType snapshotType() {
        return snapshotType;
    }

    @Override
    public Snapshot payload() {
        return (Snapshot) super.payload();
    }
}
