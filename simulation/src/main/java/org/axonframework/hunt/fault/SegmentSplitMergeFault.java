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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Splits a segment and merges it back, over and over, while the workload keeps writing.
 * <p>
 * Changing the segment count is a membership change, and membership changes are where distributed systems lose things.
 * A split hands one segment's work to two, a merge hands two segments' work to one, and both happen while events are
 * still arriving and while another node may be trying to claim the very rows being rewritten. What must survive it is
 * that every committed event is handled exactly once across all segments and that per-key order is preserved across the
 * change: an event may move to a different segment, but it may not be skipped, and the events of one key may not arrive
 * out of order because their segment changed mid-stream.
 * <p>
 * <b>A storm rather than a single instruction, and the reason is the guard.</b> A split blocks its own coordinator from
 * re-claiming the segment while the split is in progress, and clears the block when it completes; a merge does the same
 * for both halves. Doing it once measures the happy path. Doing it repeatedly, against a segment somebody is working on,
 * is what puts an instruction in the middle of another instruction's guard.
 * <p>
 * The fault runs on its own thread, because a split waits for the work package holding the segment to abort and an
 * instruction thread that blocked the fault plane would stall the whole run's phase timeline.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class SegmentSplitMergeFault implements Fault {

    private final int nodeIndex;
    private final Duration period;
    private final boolean mergeOnly;
    private final AtomicBoolean running = new AtomicBoolean();
    private final java.util.Set<Integer> outstandingSplits = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile @org.jspecify.annotations.Nullable Thread storm;
    private volatile @org.jspecify.annotations.Nullable String target;

    /**
     * Creates the fault.
     *
     * @param nodeIndex which of the run's nodes issues the instructions, by position; taken modulo the node count
     * @param period    how long to wait between one instruction and the next
     */
    public SegmentSplitMergeFault(int nodeIndex, Duration period) {
        this(nodeIndex, period, false);
    }

    /**
     * Creates a fault that only ever merges, never splits.
     * <p>
     * The one shape in which the framework's refusal to merge can be observed is a processor with a single segment, and
     * a storm that split first would have given it a second segment to merge with before the question was ever asked.
     *
     * @param nodeIndex which of the run's nodes issues the instructions, by position
     * @param period    how long to wait between one instruction and the next
     * @return the fault
     */
    public static SegmentSplitMergeFault mergesOnly(int nodeIndex, Duration period) {
        return new SegmentSplitMergeFault(nodeIndex, period, true);
    }

    private SegmentSplitMergeFault(int nodeIndex, Duration period, boolean mergeOnly) {
        this.mergeOnly = mergeOnly;
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("The nodeIndex cannot be negative, but was " + nodeIndex + ".");
        }
        this.period = Objects.requireNonNull(period, "The period cannot be null.");
        if (period.isNegative()) {
            throw new IllegalArgumentException("The period cannot be negative, but was " + period + ".");
        }
        this.nodeIndex = nodeIndex;
    }

    @Override
    public String kind() {
        return "segment-split-merge";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("nodeIndex", String.valueOf(nodeIndex),
                      "periodMs", String.valueOf(period.toMillis()),
                      "mergeOnly", String.valueOf(mergeOnly));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        List<String> nodes = site.nodeNames();
        if (nodes.isEmpty() || !running.compareAndSet(false, true)) {
            return;
        }
        String chosen = nodes.get(nodeIndex % nodes.size());
        target = chosen;
        Thread thread = new Thread(() -> storm(site, evidence, chosen), "hunt-split-merge");
        thread.setDaemon(true);
        storm = thread;
        thread.start();
    }

    private void storm(FaultSite site, FaultEvidence evidence, String target) {
        while (running.get()) {
            List<Integer> held = site.claimedSegments(target);
            if (held.isEmpty()) {
                sleep();
                continue;
            }
            int segment = held.getFirst();
            if (!mergeOnly) {
                // Evidence on the instruction rather than on the answer. The framework declining a membership change is
                // the observation in its own right -- a merge on a single-segment processor must be refused -- and a
                // fault that only recorded acceptances would report an arm built entirely around a refusal as a fault
                // that never landed.
                boolean carriedOut = site.splitSegment(target, segment);
                evidence.fired((carriedOut ? "split/" : "split-refused/") + segment);
                if (carriedOut) {
                    outstandingSplits.add(segment);
                }
            }
            sleep();
            if (!running.get()) {
                return;
            }
            // Merge the segment back, so the run's segment count returns to what the cluster has capacity for. A storm
            // that only ever split would eventually create a segment no node is allowed to claim, and the run would
            // report a liveness failure that the harness itself caused.
            boolean merged = site.mergeSegment(target, segment);
            evidence.fired((merged ? "merge/" : "merge-refused/") + segment);
            if (merged) {
                outstandingSplits.remove(segment);
            }
            sleep();
        }
    }

    private void sleep() {
        if (period.isZero()) {
            return;
        }
        try {
            Thread.sleep(period.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    @Override
    public void deactivate(FaultSite site) {
        running.set(false);
        Thread thread = storm;
        if (thread != null) {
            storm = null;
            try {
                thread.join(Duration.ofSeconds(30).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Undo whatever the storm left half-done, which is what the heal phase is for. A window closing straight after a
        // split leaves the cluster one segment wider than its capacity was sized for, and the segment nobody may claim
        // then never catches up -- a liveness failure the fault caused rather than found.
        String chosen = target;
        if (chosen != null) {
            outstandingSplits.forEach(segment -> site.mergeSegment(chosen, segment));
            outstandingSplits.clear();
        }
    }
}
