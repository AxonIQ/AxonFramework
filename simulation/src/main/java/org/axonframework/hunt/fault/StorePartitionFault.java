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

import org.axonframework.hunt.harness.StoreInfrastructure;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Cuts the network between the application and its store, repeatedly, while the store keeps running.
 * <p>
 * <b>This is the fault that makes a commit acknowledgement ambiguous, and no in-heap fault can be.</b> An injected
 * refusal tells the application clearly that its append failed; a torn socket tells it nothing. The request may have
 * arrived and been applied, the reply may have been lost on the way back, or neither may have happened, and the
 * application cannot tell which -- so the durability question stops being "did the store keep it" and becomes "did the
 * store keep something the client was never told about". A run that produces no such outcome has not exercised the
 * question at all, which is why the scenario built on this fault reports zero unknowns as inconclusive.
 * <p>
 * The store is left running throughout on purpose. It keeps its state, its clock and its open transactions, so an
 * interrupted commit is decided by the store after the client has stopped listening, which is precisely the case a
 * crash cannot produce.
 * <p>
 * Each cut is one landing, evidenced by the proxy's own reported state before and after. A run on a store with no
 * infrastructure records no landing, which is how the same scenario reports itself unverified in the heap instead of
 * passing.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class StorePartitionFault implements Fault {

    private final Duration cut;
    private final int cuts;
    private final Duration between;

    /**
     * Creates the fault.
     *
     * @param cut     how long each partition lasts
     * @param cuts    how many partitions the window contains
     * @param between how long the network is whole between two partitions
     */
    public StorePartitionFault(Duration cut, int cuts, Duration between) {
        this.cut = Objects.requireNonNull(cut, "The cut cannot be null.");
        this.between = Objects.requireNonNull(between, "The between cannot be null.");
        if (cuts < 1) {
            throw new IllegalArgumentException("The cuts must be at least one, but was " + cuts + ".");
        }
        this.cuts = cuts;
    }

    @Override
    public String kind() {
        return "store-partition";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("cutMs", String.valueOf(cut.toMillis()),
                      "cuts", String.valueOf(cuts),
                      "betweenMs", String.valueOf(between.toMillis()));
    }

    /**
     * Cuts the network the declared number of times, blocking for the whole sequence.
     * <p>
     * Blocking is deliberate: the runner drives a fault window from its own thread, and a partition that returned
     * immediately would have to heal itself from a thread nobody joins, leaving the heal phase racing the cut it was
     * supposed to follow.
     */
    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        StoreInfrastructure infrastructure = site.infrastructure();
        for (int index = 0; index < cuts; index++) {
            StoreInfrastructure.Evidence cutEvidence = infrastructure.interruptConnections(cut);
            if (!cutEvidence.landed()) {
                return;
            }
            evidence.fired(cutEvidence.describe());
            if (index < cuts - 1) {
                sleep(between);
            }
        }
    }

    @Override
    public void deactivate(FaultSite site) {
        // Every cut heals itself before activate returns, so the heal phase starts on a whole network.
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
