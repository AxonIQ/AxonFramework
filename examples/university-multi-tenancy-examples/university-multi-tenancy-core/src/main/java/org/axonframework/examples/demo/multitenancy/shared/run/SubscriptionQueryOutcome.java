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
 * What the tenant-aware subscription-query demonstration observed. Unlike per-tenant event storage,
 * snapshotting, and event processing, this holds identically in memory and against Axon Server, so there is
 * no not-demonstrated case: both tenants known at startup subscribe to their own statistics before either
 * enrolls a student.
 *
 * @param springfieldUpdatesReceived the number of updates Springfield's own subscription received, including
 *                                   its initial result
 * @param shelbyvilleUpdatesReceived the number of updates Shelbyville's own subscription received, including
 *                                   its initial result
 * @param isolatedByTenant           whether each subscription received exactly as many updates as its own
 *                                   tenant's accepted enrollments account for, and so none of the other
 *                                   tenant's updates
 */
public record SubscriptionQueryOutcome(int springfieldUpdatesReceived,
                                       int shelbyvilleUpdatesReceived,
                                       boolean isolatedByTenant) {

}
