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
import java.util.Objects;

/**
 * One stretch of a run during which a set of faults is installed.
 * <p>
 * The window's identifier is stamped on every history record written while it is open, so a checker can tell an
 * anomaly that happened under fault from one that happened in the clear. Without that, a finding cannot be
 * attributed, and an unattributable finding is an argument rather than a bug report.
 *
 * @param id       the window's identifier, stamped on every record written while it is open
 * @param delay    how long after the warmup phase ends the window opens
 * @param duration how long the window stays open
 * @param faults   the faults installed for the duration of the window
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record FaultWindow(String id, Duration delay, Duration duration, List<Fault> faults) {

    /**
     * Compact constructor rejecting missing parts, negative timings, and an empty fault set.
     */
    public FaultWindow {
        Objects.requireNonNull(id, "The id cannot be null.");
        Objects.requireNonNull(delay, "The delay cannot be null.");
        Objects.requireNonNull(duration, "The duration cannot be null.");
        faults = List.copyOf(Objects.requireNonNull(faults, "The faults cannot be null."));
        if (delay.isNegative() || duration.isNegative()) {
            throw new IllegalArgumentException("A fault window cannot have a negative delay or duration.");
        }
        if (faults.isEmpty()) {
            throw new IllegalArgumentException("A fault window [" + id + "] must declare at least one fault.");
        }
    }

    /**
     * Creates a window opening immediately after warmup.
     *
     * @param id       the window's identifier
     * @param duration how long the window stays open
     * @param faults   the faults installed for its duration
     * @return the window
     */
    public static FaultWindow immediately(String id, Duration duration, Fault... faults) {
        return new FaultWindow(id, Duration.ZERO, duration, List.of(faults));
    }

    /**
     * Returns the moment, relative to the end of warmup, at which the window closes.
     *
     * @return the delay plus the duration
     */
    public Duration end() {
        return delay.plus(duration);
    }

    /**
     * Indicates whether this window is open at any moment another window is.
     *
     * @param other the window to compare against
     * @return {@code true} when the two windows overlap in time
     */
    public boolean overlaps(FaultWindow other) {
        Objects.requireNonNull(other, "The other window cannot be null.");
        return delay.compareTo(other.end()) < 0 && other.delay().compareTo(end()) < 0;
    }
}
