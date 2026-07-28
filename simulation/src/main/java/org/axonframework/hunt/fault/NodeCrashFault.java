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

import java.util.List;
import java.util.Map;

/**
 * Drops one node for the length of the window and brings it back when the window closes.
 * <p>
 * The node is not shut down, it is abandoned: its threads are cancelled and nothing releases the claims it held, so
 * its segments stay owned by a node that has stopped extending them and can only be recovered by another node
 * stealing them once they expire. That state is what the claim algebra exists for, and an orderly shutdown never
 * produces it, because an orderly shutdown gives the claims back.
 * <p>
 * Bringing the node back under the same identity when the window closes is the other half of the experiment. A
 * restarted process is not a new one: its claims are still recorded under its own name, so it may re-take them
 * immediately without waiting for anything to expire, and whether the cluster agrees about that is the question.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class NodeCrashFault implements Fault {

    private final int nodeIndex;
    private final boolean aimAtTheBusiest;
    private volatile String crashed = "";

    /**
     * Creates the fault.
     *
     * @param nodeIndex which of the run's nodes to drop, by position; taken modulo the node count
     */
    public NodeCrashFault(int nodeIndex) {
        this(nodeIndex, false);
    }

    private NodeCrashFault(int nodeIndex, boolean aimAtTheBusiest) {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("The nodeIndex cannot be negative, but was " + nodeIndex + ".");
        }
        this.nodeIndex = nodeIndex;
        this.aimAtTheBusiest = aimAtTheBusiest;
    }

    /**
     * Creates the fault aimed at whichever node holds the most segments when the window opens.
     * <p>
     * A crash aimed by position lands on a node holding nothing whenever the cluster has any headroom, and a crash that
     * takes down an idle node produces no claim handover at all. Aiming at the busiest node is what makes the fault
     * reach the thing it is for.
     *
     * @return the fault
     */
    public static NodeCrashFault busiest() {
        return new NodeCrashFault(0, true);
    }

    @Override
    public String kind() {
        return "node-crash";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("nodeIndex", aimAtTheBusiest ? "busiest" : String.valueOf(nodeIndex));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        List<String> nodes = site.nodeNames();
        if (nodes.isEmpty()) {
            return;
        }
        String target = aimAtTheBusiest ? site.busiestNode(nodeIndex) : nodes.get(nodeIndex % nodes.size());
        if (target == null) {
            return;
        }
        crashed = target;
        site.crashNode(target);
        evidence.fired(target);
    }

    @Override
    public void deactivate(FaultSite site) {
        String target = crashed;
        if (!target.isEmpty()) {
            crashed = "";
            site.restartNode(target);
        }
    }
}
