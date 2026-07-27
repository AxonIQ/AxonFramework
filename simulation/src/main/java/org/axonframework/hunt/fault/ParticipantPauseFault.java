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
 * Freezes one participant while everything else keeps running.
 * <p>
 * This is the fault a crash can never produce. A killed process is gone: its claims expire, its work is retried, and
 * every guard in the system is designed for it. A frozen process is alive, still believes it holds everything it held
 * a moment ago, and comes back mid-operation into a world that has moved on. That is where the lease bugs live, and
 * it is why every serious fault suite runs a pause nemesis.
 * <p>
 * The stall is deliberately longer than every timeout in the run's timescale, so that anything time-based has had the
 * chance to give up on the frozen participant before it wakes.
 * <p>
 * A participant freezes at its own checkpoint rather than being suspended from outside. Suspending a thread that
 * happens to hold a framework lock would wedge the run instead of testing it, which measures nothing.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class ParticipantPauseFault implements Fault {

    private final Duration stall;
    private final int participantIndex;

    /**
     * Creates the fault.
     *
     * @param stall            how long the participant stays frozen; longer than any relevant timeout
     * @param participantIndex which of the run's participants to freeze, by position
     */
    public ParticipantPauseFault(Duration stall, int participantIndex) {
        this.stall = Objects.requireNonNull(stall, "The stall cannot be null.");
        if (stall.isNegative() || stall.isZero()) {
            throw new IllegalArgumentException("The stall must be positive, but was " + stall + ".");
        }
        if (participantIndex < 0) {
            throw new IllegalArgumentException(
                    "The participantIndex cannot be negative, but was " + participantIndex + ".");
        }
        this.participantIndex = participantIndex;
    }

    @Override
    public String kind() {
        return "participant-pause";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("stallMs", String.valueOf(stall.toMillis()),
                      "participantIndex", String.valueOf(participantIndex));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        List<String> participants = site.participants();
        if (participants.isEmpty()) {
            return;
        }
        String target = participants.get(participantIndex % participants.size());
        // The evidence is recorded when the participant is actually held, not when the stall is armed: a stall nobody
        // ever reaches has not landed, and saying otherwise would let a run pass on a fault that never happened.
        site.pauses().pause(target, stall, stalled -> evidence.fired(target + "/" + stalled.toMillis() + "ms"));
    }

    @Override
    public void deactivate(FaultSite site) {
        site.pauses().resumeAll();
    }
}
