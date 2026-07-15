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

package org.axonframework.examples.demo.multitenancy.scaffolding;

/**
 * What a demo run observed, returned by the run, so the smoke test can assert the outcome through the
 * same entry point a user runs.
 *
 * @param springfieldEnrolments      the enrolments recorded in Springfield's course-statistics repository
 * @param springfieldAuditEntries    the entries recorded in Springfield's audit log
 * @param ogdenvilleEnrolments       the enrolments recorded in the runtime-added Ogdenville's repository
 * @param unknownTenantRejected      whether an event for an unknown tenant was rejected
 * @param ambiguousProvidersRejected whether two providers for one type were rejected at configuration time
 * @param shelbyvilleClosedOnRemoval whether Shelbyville's instances were closed when its tenant was removed
 * @param allClosedOnShutdown        whether every remaining tenant's instances were closed on shutdown
 */
public record DemoOutcome(int springfieldEnrolments,
                          int springfieldAuditEntries,
                          int ogdenvilleEnrolments,
                          boolean unknownTenantRejected,
                          boolean ambiguousProvidersRejected,
                          boolean shelbyvilleClosedOnRemoval,
                          boolean allClosedOnShutdown) {

}
