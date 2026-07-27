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

package org.axonframework.hunt.scenario;

/**
 * How hard a run is allowed to push, and how many faults it may combine.
 * <p>
 * The fault-composition limit is the reason the tiers exist. Starting with compound faults destroys attribution:
 * something broke, and there are four candidate causes and no way to separate them. One fault at a time first, pairs
 * once the single-fault behaviour is understood, and storms only where nobody is expected to be reading the result by
 * hand.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public enum Tier {

    /**
     * The tier that runs on every change: a small workload, a handful of seeds, and at most one fault at a time.
     */
    SMOKE(1),

    /**
     * The tier that runs on a schedule: a large workload, many seeds, and faults in pairs.
     */
    HARDENING(2),

    /**
     * The tier that runs before a release: seeded storms with no limit on how many faults overlap.
     */
    RELEASE(Integer.MAX_VALUE);

    private final int maxConcurrentFaults;

    Tier(int maxConcurrentFaults) {
        this.maxConcurrentFaults = maxConcurrentFaults;
    }

    /**
     * Returns how many faults may be installed at the same time at this tier.
     *
     * @return the limit; a schedule exceeding it is rejected before the run starts
     */
    public int maxConcurrentFaults() {
        return maxConcurrentFaults;
    }
}
