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

import org.axonframework.messaging.core.QualifiedName;

import java.util.Objects;
import java.util.Set;

/**
 * A name a snapshot may be stored under, together with the snapshot versions that are acceptable under that name.
 * <p>
 * Only a snapshot stored under the given {@link #qualifiedName() name} with a version in the
 * {@link #acceptableVersions() acceptable versions} qualifies. Versions are matched for membership only: a version
 * outside the set is unusable however it relates to the versions inside it, so a snapshot written by a newer version
 * of an application is passed over by an older one that cannot read it.
 * <p>
 * An empty set accepts a snapshot of any version. This is deprecated: a caller always knows which versions it can
 * read, and should state them, as accepting any version is what lets an unreadable snapshot cause a full replay.
 *
 * @param qualifiedName      the name a snapshot may be stored under, cannot be {@code null}
 * @param acceptableVersions the snapshot versions acceptable under that name, cannot be {@code null}, and cannot
 *                           contain {@code null}; an empty set accepts any version, which is deprecated
 * @author John Hendrikx
 * @since 5.5.0
 */
public record SnapshotCandidate(QualifiedName qualifiedName, Set<String> acceptableVersions) {

    /*
     * Temporary: an empty set (any version) is accepted only so Axoniq Framework keeps compiling and working through
     * the deprecated SourcingStrategy.Snapshot constructor that has no version to offer. Once it has migrated, an
     * empty set is rejected with an IllegalArgumentException again, and accepts() reduces to a plain contains().
     */
    /**
     * Constructs a new instance.
     *
     * @throws NullPointerException if {@code qualifiedName} or {@code acceptableVersions} is {@code null}, or
     *                              {@code acceptableVersions} contains {@code null}
     */
    public SnapshotCandidate {
        Objects.requireNonNull(qualifiedName, "The qualifiedName parameter cannot be null.");
        acceptableVersions = Set.copyOf(
            Objects.requireNonNull(acceptableVersions, "The acceptableVersions parameter cannot be null.")
        );
    }

    /**
     * Returns whether a snapshot of the given {@code version} is acceptable under this candidate.
     *
     * @param version the version of a snapshot, cannot be {@code null}
     * @return {@code true} if the version is among the acceptable versions, or the acceptable versions are empty,
     * otherwise {@code false}
     * @throws NullPointerException if {@code version} is {@code null}
     */
    public boolean accepts(String version) {
        Objects.requireNonNull(version, "The version parameter cannot be null.");

        // temporary, see the note on the constructor
        return acceptableVersions.isEmpty() || acceptableVersions.contains(version);
    }
}
