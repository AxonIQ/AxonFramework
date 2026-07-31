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
 * What the tenant-aware subscription-query demonstration observed. It needs each tenant to have its own event
 * store, since only then does a projection run to tell subscribers about a change, so it only runs on the Axon
 * Server paths. In memory it is {@link #notDemonstrated() not demonstrated}.
 *
 * @param demonstrated               whether the subscription-query demonstration ran (only against Axon Server)
 * @param springfieldUpdatesReceived the number of updates Springfield's own subscription received, including its
 *                                   initial result
 * @param shelbyvilleUpdatesReceived the number of updates Shelbyville's own subscription received, including its
 *                                   initial result
 * @param isolatedByTenant           whether each subscription saw exactly its own tenant's enrollments arriving
 *                                   one at a time, and so none of the other tenant's updates
 * @param completionScopedToTenant   whether Springfield running out of seats completed only Springfield's
 *                                   subscription, leaving Shelbyville's open on its own free seat
 * @author Jakob Hatzl
 * @author Laura Devriendt
 * @since 5.3.0
 */
public record SubscriptionQueryOutcome(boolean demonstrated,
                                       int springfieldUpdatesReceived,
                                       int shelbyvilleUpdatesReceived,
                                       boolean isolatedByTenant,
                                       boolean completionScopedToTenant) {

    /**
     * The outcome of a run that exercised tenant-aware subscription queries, carrying what it observed.
     *
     * @param springfieldUpdatesReceived the updates Springfield's own subscription received
     * @param shelbyvilleUpdatesReceived the updates Shelbyville's own subscription received
     * @param isolatedByTenant           whether neither subscription received the other tenant's updates
     * @param completionScopedToTenant   whether only the tenant out of seats had its subscription completed
     * @return an outcome marked as demonstrated
     */
    public static SubscriptionQueryOutcome demonstratedWith(int springfieldUpdatesReceived,
                                                            int shelbyvilleUpdatesReceived,
                                                            boolean isolatedByTenant,
                                                            boolean completionScopedToTenant) {
        return new SubscriptionQueryOutcome(true,
                                            springfieldUpdatesReceived,
                                            shelbyvilleUpdatesReceived,
                                            isolatedByTenant,
                                            completionScopedToTenant);
    }

    /**
     * The outcome of a run that did not exercise tenant-aware subscription queries, such as the in-memory demo.
     *
     * @return an outcome marked as not demonstrated
     */
    public static SubscriptionQueryOutcome notDemonstrated() {
        return new SubscriptionQueryOutcome(false, 0, 0, false, false);
    }
}
