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
    private final boolean atCommitBoundary;
    private final java.util.concurrent.atomic.AtomicInteger cutsMade =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile @org.jspecify.annotations.Nullable StoreHook hook;

    /**
     * Creates the fault, timed on a wall clock.
     *
     * @param cut     how long each partition lasts
     * @param cuts    how many partitions the window contains
     * @param between how long the network is whole between two partitions
     */
    public StorePartitionFault(Duration cut, int cuts, Duration between) {
        this(cut, cuts, between, false);
    }

    private StorePartitionFault(Duration cut, int cuts, Duration between, boolean atCommitBoundary) {
        this.cut = Objects.requireNonNull(cut, "The cut cannot be null.");
        this.between = Objects.requireNonNull(between, "The between cannot be null.");
        if (cuts < 1) {
            throw new IllegalArgumentException("The cuts must be at least one, but was " + cuts + ".");
        }
        this.cuts = cuts;
        this.atCommitBoundary = atCommitBoundary;
    }

    /**
     * Creates the fault aimed at the commit boundary rather than at the clock.
     * <p>
     * <b>A partition timed on a wall clock is a nemesis that only sometimes lands where the claim is.</b> The window an
     * acknowledgement is ambiguous in is the few milliseconds between a commit being sent and its reply arriving, so a
     * cut placed by elapsed time hits one only by coincidence -- and an arm that asserts the ambiguity it produces is then
     * flaky by construction, which is worse than an arm that does not assert it. Aiming the cut at the store boundary
     * instead removes the coincidence: the network goes down after the rows have been written and before the transaction
     * commits, so the commit is attempted across a dead connection every time.
     * <p>
     * The heal is scheduled rather than awaited, because the point is to return with the network still down.
     *
     * @param cut  how long each partition lasts before it heals itself
     * @param cuts how many commits are cut into
     * @return the fault
     */
    public static StorePartitionFault atCommitBoundary(Duration cut, int cuts) {
        return new StorePartitionFault(cut, cuts, Duration.ZERO, true);
    }

    @Override
    public String kind() {
        return "store-partition";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("cutMs", String.valueOf(cut.toMillis()),
                      "cuts", String.valueOf(cuts),
                      "betweenMs", String.valueOf(between.toMillis()),
                      "aimedAt", atCommitBoundary ? "commit-boundary" : "wall-clock");
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
        if (atCommitBoundary) {
            StoreHook installed = new StoreHook() {
                @Override
                public void afterAppend(AppendAttempt attempt) {
                    if (cutsMade.getAndIncrement() >= cuts) {
                        return;
                    }
                    StoreInfrastructure.Evidence cutEvidence = infrastructure.cutConnections();
                    if (!cutEvidence.landed()) {
                        return;
                    }
                    // Scheduled rather than awaited: the whole point is to return from here with the network still down,
                    // so that the transaction this append belongs to is committed across a broken connection.
                    healAfter(infrastructure, cut);
                    evidence.fired(cutEvidence.describe() + "; aimed at the commit of " + attempt.describe());
                }
            };
            hook = installed;
            site.installStoreHook(installed);
            return;
        }
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
        StoreHook installed = hook;
        if (installed != null) {
            site.removeStoreHook(installed);
            hook = null;
        }
        // The network must be whole when the heal phase starts, whichever way it was cut. Healing an already-whole
        // network is a no-op, so this is safe to do unconditionally and is the only guarantee that a scheduled heal
        // which lost its race with the end of the window does not leave the run partitioned.
        site.infrastructure().healConnections();
    }

    private static void healAfter(StoreInfrastructure infrastructure, Duration after) {
        Thread healer = new Thread(() -> {
            sleep(after);
            infrastructure.healConnections();
        }, "hunt-partition-heal");
        healer.setDaemon(true);
        healer.start();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
