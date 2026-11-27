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

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.QualifiedName;

import java.util.Objects;

/**
 * A snapshot found by a {@link org.axonframework.eventsourcing.snapshot.store.SnapshotStore}, together with the name it
 * was found under.
 * <p>
 * The name is that of the candidate that matched, which is not necessarily the name the snapshotted entity is
 * currently known by.
 * <p>
 * Internal, as it exists only as the result of
 * {@link org.axonframework.eventsourcing.snapshot.store.SnapshotStore#load}, which is internal too.
 *
 * @param qualifiedName the name the snapshot was found under, cannot be {@code null}
 * @param snapshot      the snapshot that was found, cannot be {@code null}
 * @author John Hendrikx
 * @since 5.5.0
 */
@Internal
public record SnapshotMatch(QualifiedName qualifiedName, Snapshot snapshot) {

    /**
     * Constructs a new instance.
     *
     * @throws NullPointerException if any argument is {@code null}
     */
    public SnapshotMatch {
        Objects.requireNonNull(qualifiedName, "The qualifiedName parameter cannot be null.");
        Objects.requireNonNull(snapshot, "The snapshot parameter cannot be null.");
    }
}
