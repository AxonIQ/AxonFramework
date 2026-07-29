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

package org.axonframework.examples.demo.multitenancy.shared.run;

/**
 * What the per-tenant snapshotting demonstration observed. Per-tenant snapshot storage is a feature of Axon
 * Server, where each tenant's snapshots live in its own context, so this demonstration only runs on the Axon
 * Server paths. In memory every tenant shares one snapshot store, so it is {@link #notDemonstrated() not
 * demonstrated} and {@link #demonstrated()} is {@code false}.
 *
 * @param demonstrated                   whether the snapshotting demonstration ran (only against Axon Server)
 * @param bothTenantsHoldOwnSnapshot     whether both tenants' own stores hold a snapshot of the same course
 * @param snapshotsHoldTheirOwnStudents  whether each snapshot holds only its own tenant's student, proving
 *                                       neither tenant read the other's snapshot
 */
public record SnapshottingOutcome(boolean demonstrated,
                                  boolean bothTenantsHoldOwnSnapshot,
                                  boolean snapshotsHoldTheirOwnStudents) {

    /**
     * The outcome of a run that exercised per-tenant snapshotting, carrying what it observed.
     *
     * @param bothTenantsHoldOwnSnapshot    whether both tenants' own stores hold a snapshot of the same course
     * @param snapshotsHoldTheirOwnStudents whether each snapshot holds only its own tenant's student
     * @return an outcome marked as demonstrated
     */
    public static SnapshottingOutcome demonstratedWith(boolean bothTenantsHoldOwnSnapshot,
                                                       boolean snapshotsHoldTheirOwnStudents) {
        return new SnapshottingOutcome(true, bothTenantsHoldOwnSnapshot, snapshotsHoldTheirOwnStudents);
    }

    /**
     * The outcome of a run that did not exercise per-tenant snapshotting, such as the in-memory demo.
     *
     * @return an outcome marked as not demonstrated
     */
    public static SnapshottingOutcome notDemonstrated() {
        return new SnapshottingOutcome(false, false, false);
    }
}
