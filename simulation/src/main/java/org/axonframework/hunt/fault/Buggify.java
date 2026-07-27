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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Seeded probabilistic perturbation points that bias a run towards rare interleavings.
 * <p>
 * A boundary the operating-system scheduler visits once in a million runs is a boundary this suite would never test.
 * A named point placed at that boundary yields, and occasionally parks, with a seeded probability, so the window
 * opens often enough to be explored in a run that lasts seconds.
 * <p>
 * Every point lives in a harness wrapper, never in framework code: the suite observes Axon Framework and does not
 * modify it, so a perturbation point inside the engine would change the thing being measured.
 * <p>
 * An instance is inert until {@link #activate()} is called and inert again after {@link #deactivate()}, which returns
 * how often each point fired so the run can prove its perturbation landed.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class Buggify {

    /**
     * The point reached before an append is handed to the store.
     */
    public static final String BEFORE_APPEND = "store.before-append";

    /**
     * The point reached inside the commit, between the conflict check and the write.
     */
    public static final String BEFORE_COMMIT = "store.before-commit";

    /**
     * The point reached after a commit has been made visible.
     */
    public static final String AFTER_COMMIT = "store.after-commit";

    private final Random random;
    private final double probability;
    private final ConcurrentHashMap<String, AtomicInteger> fired = new ConcurrentHashMap<>();
    private volatile boolean active;

    /**
     * Creates a set of perturbation points.
     *
     * @param seed        the seed deciding, per reached point, whether to perturb
     * @param probability the chance in {@code [0,1]} that a reached point perturbs scheduling
     */
    public Buggify(long seed, double probability) {
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("The probability must be in [0,1], but was " + probability + ".");
        }
        this.random = new Random(seed);
        this.probability = probability;
    }

    /**
     * Creates points that never fire, for a run that does not want scheduling bias.
     *
     * @return an inert instance
     */
    public static Buggify inert() {
        return new Buggify(0L, 0.0);
    }

    /**
     * Arms every point.
     */
    public void activate() {
        active = true;
    }

    /**
     * Disarms every point and reports what fired.
     *
     * @return how often each named point perturbed scheduling while armed
     */
    public Map<String, Integer> deactivate() {
        active = false;
        Map<String, Integer> snapshot = new HashMap<>();
        fired.forEach((point, count) -> snapshot.put(point, count.get()));
        return Map.copyOf(snapshot);
    }

    /**
     * Reaches a named perturbation point.
     *
     * @param point the point's stable name, for example {@link #BEFORE_COMMIT}
     */
    public void fire(String point) {
        Objects.requireNonNull(point, "The point cannot be null.");
        if (!active) {
            return;
        }
        boolean perturb;
        synchronized (random) {
            perturb = random.nextDouble() < probability;
        }
        if (!perturb) {
            return;
        }
        int count = fired.computeIfAbsent(point, ignored -> new AtomicInteger()).incrementAndGet();
        Thread.yield();
        if (count % 4 == 0) {
            LockSupport.parkNanos(200_000L);
        }
    }

    /**
     * Returns how often the named point has perturbed scheduling so far.
     *
     * @param point the point's name
     * @return the fire count
     */
    public int fires(String point) {
        AtomicInteger count = fired.get(point);
        return count == null ? 0 : count.get();
    }
}
