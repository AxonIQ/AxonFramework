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

import java.util.List;

/**
 * What the tenant-aware event processing demonstration observed. It needs each tenant to have its own event
 * store, so it only runs on the Axon Server paths. In memory it is {@link #notDemonstrated() not demonstrated}.
 *
 * @param demonstrated           whether the event-processing demonstration ran (only against Axon Server)
 * @param processorNames         the names of every streaming event processor the application registered, which
 *                               should be the projection's alone
 * @param springfieldProjected   the enrollments the projection wrote into Springfield's own read model
 * @param shelbyvilleProjected   the enrollments the projection wrote into Shelbyville's own read model, proving one
 *                               processor kept the two tenants' read models apart
 * @param ogdenvilleProjected   the enrollments the projection wrote into the runtime-added Ogdenville's read
 *                               model, proving the stream re-opened with a tenant that did not exist when the
 *                               processor started
 * @author Laura Devriendt
 * @since 5.3.0
 */
public record StreamingOutcome(boolean demonstrated,
                               List<String> processorNames,
                               int springfieldProjected,
                               int shelbyvilleProjected,
                               int ogdenvilleProjected) {

    /**
     * The outcome of a run that exercised tenant-aware event processing, carrying what it observed.
     *
     * @param processorNames         the names of every registered streaming event processor
     * @param springfieldProjected   the enrollments projected into Springfield's own read model
     * @param shelbyvilleProjected   the enrollments projected into Shelbyville's own read model
     * @param ogdenvilleProjected the enrollments projected into the runtime-added tenant's read model
     * @return an outcome marked as demonstrated
     */
    public static StreamingOutcome demonstratedWith(List<String> processorNames,
                                                    int springfieldProjected,
                                                    int shelbyvilleProjected,
                                                    int ogdenvilleProjected) {
        return new StreamingOutcome(true,
                                    List.copyOf(processorNames),
                                    springfieldProjected,
                                    shelbyvilleProjected,
                                    ogdenvilleProjected);
    }

    /**
     * The outcome of a run that did not exercise tenant-aware event processing, such as the in-memory demo.
     *
     * @return an outcome marked as not demonstrated
     */
    public static StreamingOutcome notDemonstrated() {
        return new StreamingOutcome(false, List.of(), 0, 0, 0);
    }
}
