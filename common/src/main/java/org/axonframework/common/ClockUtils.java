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

package org.axonframework.common;

import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Global access point for the framework's current {@link Clock}.
 * <p>
 * This utility provides a shared clock abstraction that can be read from anywhere in the framework and overridden in
 * tests or integration scenarios. It also implements {@link InstantSource} so callers can use it wherever an instant
 * source is required.
 *
 * @author Jan Galinski
 * @since 5.2.0
 */
public final class ClockUtils  {

    /**
     * A supplier that returns the current instant of the global clock.
     */
    public static final Supplier<Instant> SUPPLIER = ClockUtils::instant;

    /**
     * The default global clock used by the framework.
     * <p>
     * This clock is UTC-based and is restored by {@link #reset()}.
     */
    private static final Clock DEFAULT_CLOCK = Clock.systemUTC();

    private static final AtomicReference<Clock> CLOCK = new AtomicReference<>(DEFAULT_CLOCK);

    /**
     * Returns the currently configured global clock.
     *
     * @return the shared clock instance, or the default UTC clock if none has been configured explicitly
     */
    public static Clock get() {
        return CLOCK.get();
    }

    /**
     * Replaces the currently configured global clock.
     *
     * @param clock the new global clock to use
     * @return the clock that was installed
     */
    public static Clock set(Clock clock) {
        CLOCK.set(clock);
        return clock;
    }

    /**
     * Restores the global clock to the default UTC clock.
     *
     * @return the default UTC clock
     */
    public static Clock reset() {
        return set(DEFAULT_CLOCK);
    }

    /**
     * Adapts the current clock to the {@link InstantSource} interface.
     *
     * @return the {@link InstantSource#instant()} of the current clock
     */
    public static Instant instant() {
        return get().instant();
    }

    private ClockUtils() {
        // do not instantiate
    }
}
