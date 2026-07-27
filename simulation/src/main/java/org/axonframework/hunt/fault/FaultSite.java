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

package org.axonframework.hunt.fault;

/**
 * Everything a fault is allowed to reach.
 * <p>
 * Passing a site rather than the running system is what keeps the fault registry open: a new fault kind is written
 * against this interface and needs no change anywhere else. It is also what keeps faults honest, because the only way
 * to reach the system is through a seam the harness owns.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public interface FaultSite {

    /**
     * Installs a hook the store wrapper will consult around every append and commit.
     *
     * @param hook the hook to install
     */
    void installStoreHook(StoreHook hook);

    /**
     * Removes a previously installed hook.
     *
     * @param hook the hook to remove
     */
    void removeStoreHook(StoreHook hook);

    /**
     * Returns the seam that stalls one participant while the rest of the system runs.
     *
     * @return the pause point
     */
    PausePoint pauses();

    /**
     * Returns the scheduling-bias points, for a fault that wants rare interleavings visited more often.
     *
     * @return the perturbation points
     */
    Buggify buggify();

    /**
     * Returns the workload participants the run has, so a fault can pick one to target.
     *
     * @return the participant names, in a stable order
     */
    java.util.List<String> participants();
}
