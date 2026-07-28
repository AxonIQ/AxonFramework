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

    /**
     * Returns the framework nodes the run has, so a fault can pick one to break.
     * <p>
     * A node is a different kind of target from a workload participant: a participant writes, a node reads and holds
     * the claims that let it. The default is empty, which is what a site with no cluster has.
     *
     * @return the node identities, in a stable order
     */
    default java.util.List<String> nodeNames() {
        return java.util.List.of();
    }

    /**
     * Drops the named node without releasing anything it holds.
     * <p>
     * The default does nothing, which is what a site with no cluster can do.
     *
     * @param nodeId the node to drop
     */
    default void crashNode(String nodeId) {
        // A site with no cluster has no node to crash.
    }

    /**
     * Brings the named node back under the same identity.
     * <p>
     * The default does nothing, which is what a site with no cluster can do.
     *
     * @param nodeId the node to bring back
     */
    default void restartNode(String nodeId) {
        // A site with no cluster has no node to restart.
    }
}
