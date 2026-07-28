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
 * Freezes the store's process for longer than the run's timeouts, and then lets it continue.
 * <p>
 * <b>A kill can never produce this state, and it is the one that breaks distributed protocols.</b> A killed process is
 * gone: its locks are released, its transactions are rolled back by recovery, and every claim it held lapses honestly.
 * A frozen one is still there. It holds every lock and every open transaction, it answers nothing at all while every
 * deadline in the system expires, and then it resumes from exactly where it stopped and acts on decisions the rest of
 * the system has already moved past. That is the long garbage collection, the descheduled container and the paused
 * virtual machine, and a suite whose only failure primitive is a crash has never seen it.
 * <p>
 * Nothing raises an error while the freeze lasts. The application's threads block in the driver, timeouts fire, claims
 * lapse, and the framework decides on its own that the store is unavailable -- which makes this the fault that tests
 * whether the framework's own timeout handling is safe rather than whether it can read an error code.
 * <p>
 * The freeze is held for a caller-declared duration that a scenario must size against the claim it is testing: a freeze
 * shorter than the timeout it is meant to trip proves nothing, so the scenario states the timeout it is exceeding.
 * Landing is the paused state the infrastructure itself reports, taken while the freeze is in force.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class StoreFreezeFault implements Fault {

    private final Duration freeze;
    private final int freezes;
    private final Duration between;

    /**
     * Creates the fault.
     *
     * @param freeze  how long each freeze lasts; size it above the timeout the scenario is trying to trip
     * @param freezes how many freezes the window contains
     * @param between how long the store runs between two freezes
     */
    public StoreFreezeFault(Duration freeze, int freezes, Duration between) {
        this.freeze = Objects.requireNonNull(freeze, "The freeze cannot be null.");
        this.between = Objects.requireNonNull(between, "The between cannot be null.");
        if (freezes < 1) {
            throw new IllegalArgumentException("The freezes must be at least one, but was " + freezes + ".");
        }
        this.freezes = freezes;
    }

    @Override
    public String kind() {
        return "store-freeze";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("freezeMs", String.valueOf(freeze.toMillis()),
                      "freezes", String.valueOf(freezes),
                      "betweenMs", String.valueOf(between.toMillis()));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        StoreInfrastructure infrastructure = site.infrastructure();
        for (int index = 0; index < freezes; index++) {
            StoreInfrastructure.Evidence frozen = infrastructure.pause(freeze);
            if (!frozen.landed()) {
                return;
            }
            evidence.fired(frozen.describe());
            if (index < freezes - 1) {
                sleep(between);
            }
        }
    }

    @Override
    public void deactivate(FaultSite site) {
        // Every freeze thaws before activate returns, so the heal phase starts on a running store.
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
