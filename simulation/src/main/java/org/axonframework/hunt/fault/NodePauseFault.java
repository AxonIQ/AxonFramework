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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Freezes one node's event handling for longer than its claim can survive, while the rest of the cluster runs on.
 * <p>
 * This is the fault a crash cannot produce and the one lease protocols are actually broken by. The node is alive and
 * still believes it owns every segment it owned a moment ago; it is simply not running, so it does not extend its
 * claims, they expire, another node takes them, and the frozen node then wakes up mid-batch into a cluster that has
 * reassigned its work. Whether the effects it was midway through are repeated, and whether anything is lost in the
 * handover, is the whole question.
 * <p>
 * The stall is taken inside the node's handler, at a checkpoint the node itself reaches. Suspending the thread from
 * outside would be simpler and would frequently freeze a thread holding a framework lock, which wedges the run rather
 * than testing it.
 * <p>
 * A stall nobody reaches has not landed. The evidence is recorded when the node is actually held, not when the stall
 * is armed, so a run whose node never handled another event is reported as undecided rather than as a pass under a
 * fault that never happened.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class NodePauseFault implements Fault {

    private final Duration stall;
    private final int nodeIndex;

    /**
     * Creates the fault.
     *
     * @param stall     how long the node stays frozen; longer than the store's claim timeout, or nothing expires
     * @param nodeIndex which of the run's nodes to freeze, by position; taken modulo the node count
     */
    public NodePauseFault(Duration stall, int nodeIndex) {
        this.stall = Objects.requireNonNull(stall, "The stall cannot be null.");
        if (stall.isNegative() || stall.isZero()) {
            throw new IllegalArgumentException("The stall must be positive, but was " + stall + ".");
        }
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("The nodeIndex cannot be negative, but was " + nodeIndex + ".");
        }
        this.nodeIndex = nodeIndex;
    }

    @Override
    public String kind() {
        return "node-pause";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("stallMs", String.valueOf(stall.toMillis()),
                      "nodeIndex", String.valueOf(nodeIndex));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        List<String> nodes = site.nodeNames();
        if (nodes.isEmpty()) {
            return;
        }
        String target = nodes.get(nodeIndex % nodes.size());
        site.pauses().pause(target, stall, stalled -> evidence.fired(target + "/" + stalled.toMillis() + "ms"));
    }

    @Override
    public void deactivate(FaultSite site) {
        site.pauses().resumeAll();
    }
}
