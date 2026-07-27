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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suspends one participant while the rest of the system keeps running.
 * <p>
 * This is the safepoint-stall seam: the process is alive, holds whatever it held, and is simply not running. A crash
 * cannot produce that state, which is why a suite that only kills processes never finds the bug where a stalled owner
 * wakes up after its lease expired and commits anyway.
 * <p>
 * A participant reaches the pause by calling {@link #checkpoint(String)} at a point where being frozen is realistic:
 * before issuing an append, between two events of a batch. The harness never suspends a thread from outside, because
 * suspending a thread holding a framework lock would deadlock the run rather than test it.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class PausePoint {

    private final Map<String, Stall> stalls = new ConcurrentHashMap<>();

    /**
     * Suspends the named participant for the given duration, starting the next time it reaches a checkpoint.
     *
     * @param participant the participant to stall
     * @param duration    how long the stall lasts
     * @param onStalled   notified with the time actually spent frozen, once the participant has reached a checkpoint
     *                    and been held there; never called if the participant never reaches one
     */
    public void pause(String participant, Duration duration, java.util.function.Consumer<Duration> onStalled) {
        Objects.requireNonNull(participant, "The participant cannot be null.");
        Objects.requireNonNull(duration, "The duration cannot be null.");
        Objects.requireNonNull(onStalled, "The onStalled cannot be null.");
        stalls.put(participant, new Stall(System.nanoTime() + duration.toNanos(), onStalled));
    }

    /**
     * Lifts any stall on the named participant.
     *
     * @param participant the participant to release
     */
    public void resume(String participant) {
        stalls.remove(Objects.requireNonNull(participant, "The participant cannot be null."));
    }

    /**
     * Lifts every stall.
     */
    public void resumeAll() {
        stalls.clear();
    }

    /**
     * Blocks the calling thread if the named participant is currently stalled.
     *
     * @param participant the participant reaching the checkpoint
     * @return how long the call actually blocked; {@link Duration#ZERO} when the participant was not stalled
     * @throws InterruptedException if the thread is interrupted while stalled
     */
    public Duration checkpoint(String participant) throws InterruptedException {
        Stall stall = stalls.get(participant);
        if (stall == null) {
            return Duration.ZERO;
        }
        long start = System.nanoTime();
        long remaining = stall.resumeAtNanos() - start;
        while (remaining > 0) {
            Thread.sleep(Math.max(1L, remaining / 1_000_000L));
            Stall stillStalled = stalls.get(participant);
            if (stillStalled == null) {
                break;
            }
            remaining = stillStalled.resumeAtNanos() - System.nanoTime();
        }
        stalls.remove(participant);
        Duration stalled = Duration.ofNanos(System.nanoTime() - start);
        stall.onStalled().accept(stalled);
        return stalled;
    }

    private record Stall(long resumeAtNanos, java.util.function.Consumer<Duration> onStalled) {

    }
}
