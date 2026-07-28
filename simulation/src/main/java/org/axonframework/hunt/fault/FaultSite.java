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
     * Returns the machinery the run's store runs on, so a fault can break the infrastructure rather than a wrapper.
     * <p>
     * This is the only seam through which a fault reaches something outside the virtual machine. The default is the
     * inert one, which is what a site over an in-heap store has: a fault aimed at it records no landing and the run is
     * inconclusive rather than a pass.
     *
     * @return the store's infrastructure controls, never {@code null}
     */
    default org.axonframework.hunt.harness.StoreInfrastructure infrastructure() {
        return org.axonframework.hunt.harness.StoreInfrastructure.none();
    }

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

    /**
     * Stops the named node the way an orderly shutdown does, releasing every claim it holds.
     * <p>
     * Different from {@link #crashNode(String)} in the one way that matters: a shutdown gives the claims back, so
     * another node can take the segments immediately instead of waiting for them to lapse. A reset needs that, because
     * it claims every segment itself.
     *
     * @param nodeId the node to stop
     */
    default void stopNode(String nodeId) {
        // A site with no cluster has no node to stop.
    }

    /**
     * Stops the named node, rewinds every segment it knows to the start of the stream, and starts it again.
     * <p>
     * The stop is not decoration: the framework refuses a reset on a running processor, so a reset that is meant to
     * succeed has to shut the processor down first. The default does nothing.
     *
     * @param nodeId the node to rewind
     */
    default void resetNode(String nodeId) {
        // A site with no cluster has no node to reset.
    }

    /**
     * Asks the named node to rewind without stopping it first, and returns whatever the framework raised.
     * <p>
     * This exists so a run can record the refusal rather than assume it. The default returns {@code null}.
     *
     * @param nodeId the node to ask
     * @return the failure the framework raised, or {@code null} when it allowed the reset
     */
    default @org.jspecify.annotations.Nullable Throwable resetRunningNode(String nodeId) {
        return null;
    }

    /**
     * Returns the segments the named node currently holds, so a fault can aim at one it will really disturb.
     * <p>
     * The default is empty, which is what a site with no cluster has.
     *
     * @param nodeId the node to ask
     * @return the segment identifiers the node holds, ascending
     */
    default java.util.List<Integer> claimedSegments(String nodeId) {
        return java.util.List.of();
    }

    /**
     * Asks the named node to split the given segment in two.
     * <p>
     * The default does nothing and reports that nothing happened.
     *
     * @param nodeId    the node to instruct
     * @param segmentId the segment to split
     * @return {@code true} when the framework carried the split out
     */
    default boolean splitSegment(String nodeId, int segmentId) {
        return false;
    }

    /**
     * Asks the named node to merge the given segment with its sibling.
     * <p>
     * The default does nothing and reports that nothing happened.
     *
     * @param nodeId    the node to instruct
     * @param segmentId the segment to merge
     * @return {@code true} when the framework carried the merge out
     */
    default boolean mergeSegment(String nodeId, int segmentId) {
        return false;
    }

    /**
     * Returns the node currently holding the most segments, falling back to the node at the given position.
     * <p>
     * <b>A fault aimed at a node by position frequently lands on a node holding nothing.</b> The coordinator claims
     * greedily up to its cap, so in a cluster with any headroom the first nodes to reach the store take everything and
     * the rest idle; dropping one of those idlers is recorded as a fault that fired and perturbs the run in no way at
     * all. Measured on this harness: four nodes over eight segments left two of them with no segment at all, and a
     * crash aimed at the first node by position produced no claim handover whatsoever. Aiming at the busiest node is
     * what makes the fault land where a claim can actually change hands.
     *
     * @param fallbackIndex which node to pick when no node holds anything, by position
     * @return the node to aim at, or {@code null} when the site has no nodes
     */
    default @org.jspecify.annotations.Nullable String busiestNode(int fallbackIndex) {
        java.util.List<String> nodes = nodeNames();
        if (nodes.isEmpty()) {
            return null;
        }
        return nodes.stream()
                    .max(java.util.Comparator.comparingInt(node -> claimedSegments(node).size()))
                    .filter(node -> !claimedSegments(node).isEmpty())
                    .orElseGet(() -> nodes.get(Math.abs(fallbackIndex) % nodes.size()));
    }
}
