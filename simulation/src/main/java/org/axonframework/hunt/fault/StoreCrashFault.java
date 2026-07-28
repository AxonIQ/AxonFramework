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
 * Kills the store's process without warning, and starts it again.
 * <p>
 * <b>This is the only fault in the suite that can make the store forget something.</b> Every other one perturbs what the
 * store is asked to do; this one destroys whatever the store had accepted but not yet made durable, and it does so at
 * whatever point in its own write path the process happened to be. Whether an append the client saw acknowledged
 * survives that is not a property of the framework's code path at all -- it is a property of what the store had actually
 * flushed -- and it is the only question a durability claim is really about.
 * <p>
 * The restart is part of the fault rather than part of the heal phase, because a store that never comes back leaves
 * every oracle undecided: the comparison that matters is between what the client was told before the kill and what a
 * full scan finds after the store has recovered.
 * <p>
 * Landing is the process's own exit code, which is {@code 137} for a signalled kill, together with the recovery line the
 * store writes on its way back up and the timestamp on it. Both come from the infrastructure rather than from the
 * harness, which is what makes them evidence.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class StoreCrashFault implements Fault {

    private final Duration downtime;
    private final int kills;
    private final Duration between;

    /**
     * Creates the fault.
     *
     * @param downtime how long the store stays down before it is started again
     * @param kills    how many kill-and-restart cycles the window contains
     * @param between  how long the store runs between two cycles
     */
    public StoreCrashFault(Duration downtime, int kills, Duration between) {
        this.downtime = Objects.requireNonNull(downtime, "The downtime cannot be null.");
        this.between = Objects.requireNonNull(between, "The between cannot be null.");
        if (kills < 1) {
            throw new IllegalArgumentException("The kills must be at least one, but was " + kills + ".");
        }
        this.kills = kills;
    }

    @Override
    public String kind() {
        return "store-crash";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("downtimeMs", String.valueOf(downtime.toMillis()),
                      "kills", String.valueOf(kills),
                      "betweenMs", String.valueOf(between.toMillis()));
    }

    /**
     * This fault does <b>not</b> declare that it perturbs the store's contents, and the distinction is the whole point.
     * <p>
     * That declaration exists for faults through which the harness itself rewrites what the store holds -- a batch made
     * to vanish, a batch stored twice, a batch truncated -- and it makes the oracles that compare the store against the
     * workload's intent stop deciding, because the harness destroyed the difference they would report. A kill destroys
     * nothing of the kind: every event offered is offered honestly and stored, or not, by the store's own doing. Whether
     * an acknowledged append survives is therefore exactly the hypothesis under test, and an oracle that stopped deciding
     * here would be refusing to answer the only question the scenario asks.
     */
    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        StoreInfrastructure infrastructure = site.infrastructure();
        for (int index = 0; index < kills; index++) {
            StoreInfrastructure.Evidence killed = infrastructure.kill(downtime);
            if (!killed.landed()) {
                return;
            }
            evidence.fired(killed.describe());
            if (index < kills - 1) {
                sleep(between);
            }
        }
    }

    @Override
    public void deactivate(FaultSite site) {
        // Every cycle brings the store back before activate returns, so the heal phase starts on a running store.
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
