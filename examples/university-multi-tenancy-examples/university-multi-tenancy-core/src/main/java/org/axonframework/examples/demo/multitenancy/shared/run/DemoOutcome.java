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
 * What a demo run observed, returned by the run, so the smoke test can assert the outcome through the
 * same entry point a user runs.
 *
 * @param springfieldEnrollments     the enrollments recorded in Springfield's course-statistics store
 * @param springfieldAuditEntries    the entries recorded in Springfield's audit log
 * @param ogdenvilleEnrollments      the enrollments recorded in the runtime-added Ogdenville's store
 * @param unknownTenantRejected      whether a command for an unknown tenant was rejected
 * @param shelbyvilleClosedOnRemoval whether Shelbyville's instances were closed when its tenant was removed
 * @param allClosedOnShutdown        whether every remaining tenant's instances were closed on shutdown
 * @param eventStorage               what the per-tenant event-storage demonstration observed (only demonstrated
 *                                   against Axon Server)
 * @param snapshotting               what the per-tenant snapshotting demonstration observed (only demonstrated
 *                                   against Axon Server)
 */
public record DemoOutcome(int springfieldEnrollments,
                          int springfieldAuditEntries,
                          int ogdenvilleEnrollments,
                          boolean unknownTenantRejected,
                          boolean shelbyvilleClosedOnRemoval,
                          boolean allClosedOnShutdown,
                          EventStorageOutcome eventStorage,
                          SnapshottingOutcome snapshotting) {

}
