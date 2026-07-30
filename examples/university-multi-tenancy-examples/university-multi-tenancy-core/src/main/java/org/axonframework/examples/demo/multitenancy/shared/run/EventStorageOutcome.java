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
 * What the per-tenant event-storage demonstration observed. Per-tenant event storage is a feature of Axon
 * Server, where each tenant's events live in its own context, so this demonstration only runs on the Axon
 * Server paths. In memory it is {@link #notDemonstrated() not demonstrated}, and {@link #demonstrated()}
 * is {@code false}.
 *
 * @param demonstrated                     whether the event-storage demonstration ran (only against Axon Server)
 * @param springfieldRejectedWhenFull      whether a further enrollment into Springfield's full course was rejected
 * @param shelbyvilleAcceptedSameCourseId  whether the same course identifier still accepted its enrollments in
 *                                         Shelbyville, proving its event store did not see Springfield's events
 */
public record EventStorageOutcome(boolean demonstrated,
                                  boolean springfieldRejectedWhenFull,
                                  boolean shelbyvilleAcceptedSameCourseId) {

    /**
     * The outcome of a run that exercised per-tenant event storage, carrying what it observed.
     *
     * @param springfieldRejectedWhenFull     whether a further enrollment into Springfield's full course was rejected
     * @param shelbyvilleAcceptedSameCourseId whether the same course identifier still accepted its enrollments in
     *                                        Shelbyville
     * @return an outcome marked as demonstrated
     */
    public static EventStorageOutcome demonstratedWith(boolean springfieldRejectedWhenFull,
                                                       boolean shelbyvilleAcceptedSameCourseId) {
        return new EventStorageOutcome(true, springfieldRejectedWhenFull, shelbyvilleAcceptedSameCourseId);
    }

    /**
     * The outcome of a run that did not exercise per-tenant event storage, such as the in-memory demo.
     *
     * @return an outcome marked as not demonstrated
     */
    public static EventStorageOutcome notDemonstrated() {
        return new EventStorageOutcome(false, false, false);
    }
}
