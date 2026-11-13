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
import org.axonframework.messaging.eventhandling.GenericEventMessage;

import java.util.Objects;

/**
 * Message containing a snapshot.
 * <p>
 * This message can be emitted when calling {@link EventStoreTransaction#source(SourcingCondition)}
 * when using the {@link SourcingStrategy.Snapshot snapshot sourcing strategy} and a suitable snapshot
 * was available.
 *
 * @author John Hendrikx
 * @since 5.1.0
 */
@Internal
public class SnapshotEventMessage extends GenericEventMessage {

    /**
     * Constructs a new instance.
     * <p>
     * The {@link #type()} of this message describes the snapshot as it was stored, combining the
     * {@link QualifiedName} it was found under with the {@link Snapshot#version() version} it carries. This is
     * what allows a snapshot taken under an older name or version to be recognized and upcast, rather than
     * only compared against the type an entity is currently declared with.
     *
     * @param qualifiedName the name the snapshot was found under, cannot be {@code null}
     * @param snapshot the snapshot, cannot be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     * @throws IllegalArgumentException when the given {@code snapshot}'s version is blank or contains
     * {@value MessageType#VERSION_DELIMITER}
     */
    public SnapshotEventMessage(QualifiedName qualifiedName, Snapshot snapshot) {
        super(
            new MessageType(
                Objects.requireNonNull(qualifiedName, "The qualifiedName parameter cannot be null."),
                Objects.requireNonNull(snapshot, "The snapshot parameter cannot be null.").version()
            ),
            snapshot
        );
    }

    @Override
    public Snapshot payload() {
        return (Snapshot) super.payload();
    }
}
