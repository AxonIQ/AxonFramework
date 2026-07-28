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
 * Rewinds a processor's tokens to the start of the stream in the middle of a run, and lets the replay run.
 * <p>
 * A reset is not a failure, which is why it is worth injecting as one: it is the operator action with the widest blast
 * radius the framework offers. Every segment goes back to the beginning, every event is delivered again, and everything
 * downstream has to be able to tell the redelivery apart from a duplicate. Doing it while a workload is still writing is
 * the case an operator actually meets and the one no unit test covers.
 * <p>
 * <b>Two preconditions the framework states, and both are exercised here.</b> A reset requires that the handlers support
 * it and that the processor is not running. The second is asserted on the local virtual machine only, so this fault
 * first asks the running processor to reset -- recording whatever the framework raises, which is the evidence that the
 * refusal is real rather than assumed -- and only then shuts the node down and resets it properly.
 * <p>
 * <b>The cross-node case is measured, not asserted.</b> With more than one node the fault can reset one while the others
 * keep processing, which the framework's own precondition does not prevent because it cannot see them. Nothing here
 * claims that is safe or unsafe: the run records what happened and the scenario reports it.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class ProcessorResetFault implements Fault {

    private final int nodeIndex;
    private final boolean stopEveryNodeFirst;

    /**
     * Creates the fault.
     *
     * @param nodeIndex          which of the run's nodes issues the reset, by position; taken modulo the node count
     * @param stopEveryNodeFirst whether to shut every other node down before resetting, which is what an operator
     *                           following the documented precondition across a cluster would have to do by hand;
     *                           {@code false} leaves the other nodes running, which is the case the precondition cannot
     *                           cover
     */
    public ProcessorResetFault(int nodeIndex, boolean stopEveryNodeFirst) {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("The nodeIndex cannot be negative, but was " + nodeIndex + ".");
        }
        this.nodeIndex = nodeIndex;
        this.stopEveryNodeFirst = stopEveryNodeFirst;
    }

    @Override
    public String kind() {
        return "processor-reset";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("nodeIndex", String.valueOf(nodeIndex),
                      "stopEveryNodeFirst", String.valueOf(stopEveryNodeFirst));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        List<String> nodes = site.nodeNames();
        if (nodes.isEmpty()) {
            return;
        }
        String target = nodes.get(nodeIndex % nodes.size());
        Throwable refusal = site.resetRunningNode(target);
        if (refusal != null) {
            evidence.fired(target + "/refused-while-running/" + refusal.getClass().getSimpleName());
        }
        if (stopEveryNodeFirst) {
            // Stopped rather than crashed, and the difference decides whether the reset can happen at all: a reset
            // claims every segment it finds, and a crashed node leaves its claims in the store owned by a process that
            // is not coming back, so the reset would be refused until they lapsed. An orderly shutdown gives them back.
            nodes.stream().filter(node -> !node.equals(target)).forEach(site::stopNode);
        }
        site.resetNode(target);
        evidence.fired(target + "/reset");
    }

    @Override
    public void deactivate(FaultSite site) {
        if (stopEveryNodeFirst) {
            site.nodeNames().forEach(site::restartNode);
        }
    }
}
