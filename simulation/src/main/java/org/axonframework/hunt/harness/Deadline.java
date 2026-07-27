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

package org.axonframework.hunt.harness;

import java.time.Duration;
import java.util.Objects;

/**
 * A wall-clock stop, and the harness's primary defence against a run that hangs.
 * <p>
 * The step cap a simulator usually carries is the secondary guard, because a cap only fires while steps are being
 * taken and the interesting hang is the one where none are. A wall-clock deadline fires regardless of what the system
 * is doing, which is what makes a suite safe to leave running unattended.
 * <p>
 * A deadline is checked, never enforced by interruption: the run notices it has expired and stops cleanly with the
 * history it has written so far, because a truncated history that is honest about being truncated is worth more than
 * a killed process.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class Deadline {

    private final String label;
    private final long endNanos;

    private Deadline(String label, long endNanos) {
        this.label = label;
        this.endNanos = endNanos;
    }

    /**
     * Creates a deadline expiring after the given duration.
     *
     * @param label    what the deadline bounds, named for the message it will produce
     * @param duration how long from now the deadline expires
     * @return the deadline
     */
    public static Deadline in(String label, Duration duration) {
        Objects.requireNonNull(label, "The label cannot be null.");
        Objects.requireNonNull(duration, "The duration cannot be null.");
        return new Deadline(label, System.nanoTime() + duration.toNanos());
    }

    /**
     * Indicates whether the deadline has passed.
     *
     * @return {@code true} once the deadline is in the past
     */
    public boolean expired() {
        return System.nanoTime() >= endNanos;
    }

    /**
     * Returns how long is left.
     *
     * @return the remaining time, never negative
     */
    public Duration remaining() {
        long remaining = endNanos - System.nanoTime();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    /**
     * Returns what this deadline bounds.
     *
     * @return the label
     */
    public String label() {
        return label;
    }

    /**
     * Fails the run if the deadline has passed.
     *
     * @throws HuntDeadlineExceededException if the deadline is in the past
     */
    public void checkNotExpired() {
        if (expired()) {
            throw new HuntDeadlineExceededException("The [" + label + "] deadline expired.");
        }
    }

    /**
     * Waits until the given condition holds, the deadline expires, or the thread is interrupted.
     *
     * @param condition     what the run is waiting for
     * @param pollInterval  how often to test the condition
     * @return {@code true} when the condition held before the deadline
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean awaitUntil(java.util.function.BooleanSupplier condition, Duration pollInterval)
            throws InterruptedException {
        Objects.requireNonNull(condition, "The condition cannot be null.");
        Objects.requireNonNull(pollInterval, "The pollInterval cannot be null.");
        while (!expired()) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(Math.max(1L, Math.min(pollInterval.toMillis(), remaining().toMillis())));
        }
        return condition.getAsBoolean();
    }

    /**
     * Thrown when a run outlives the wall-clock budget it was given.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public static class HuntDeadlineExceededException extends RuntimeException {

        /**
         * Creates the exception.
         *
         * @param message which deadline expired
         */
        public HuntDeadlineExceededException(String message) {
            super(message);
        }
    }
}
