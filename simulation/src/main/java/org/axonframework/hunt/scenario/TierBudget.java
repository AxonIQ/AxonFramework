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

import java.time.Duration;
import java.util.Objects;

/**
 * What one scenario is allowed to cost at one tier.
 * <p>
 * The wall-clock budget is the harness's primary anti-hang guard, not a performance target. A run that outlives it
 * stops and says so, rather than blocking a build until somebody notices.
 *
 * @param commands   how many commands the workload issues
 * @param seeds      how many seeds the tier runs, starting from the scenario's own seed
 * @param wallBudget the longest a single seed's run may take before it is stopped and reported as undecided
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record TierBudget(int commands, int seeds, Duration wallBudget) {

    /**
     * Compact constructor rejecting a budget that cannot run.
     */
    public TierBudget {
        Objects.requireNonNull(wallBudget, "The wallBudget cannot be null.");
        if (commands < 1) {
            throw new IllegalArgumentException("A budget needs at least one command, but had " + commands + ".");
        }
        if (seeds < 1) {
            throw new IllegalArgumentException("A budget needs at least one seed, but had " + seeds + ".");
        }
        if (wallBudget.isZero() || wallBudget.isNegative()) {
            throw new IllegalArgumentException("The wallBudget must be positive, but was " + wallBudget + ".");
        }
    }
}
