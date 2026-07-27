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

package org.axonframework.hunt.workload;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * The shape of a run's contention, derived entirely from its seed.
 * <p>
 * How many writers there are matters far less than how their accesses overlap. A uniform spread over a thousand tags
 * almost never produces a conflict; a Zipfian spread over the same thousand puts most of the traffic on a handful of
 * them, and the conflict paths that only run under contention start running. Volume buys very little coverage;
 * distribution buys most of it.
 * <p>
 * Every knob here is a pure function of the seed, and the whole record is written into the history header, so a run
 * can be reproduced from its own history without consulting the code that generated it.
 *
 * @param seed           the seed every knob is derived from
 * @param writers        how many writer threads issue commands concurrently
 * @param accounts       how many distinct accounts exist, which is the tag cardinality
 * @param distribution   how a writer picks the accounts it touches
 * @param overlapDegree  how many accounts, out of the hot set, every writer shares with every other
 * @param minBatch       the smallest number of transfers a writer issues back to back
 * @param maxBatch       the largest number of transfers a writer issues back to back
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record SwarmShape(long seed,
                         int writers,
                         int accounts,
                         Distribution distribution,
                         int overlapDegree,
                         int minBatch,
                         int maxBatch) {

    private static final List<Integer> WRITER_CHOICES = List.of(2, 4, 8, 16);

    /**
     * The exponent of the Zipfian distribution, fixed at one: the classic hot-key shape, where the most popular
     * account is touched twice as often as the second and three times as often as the third.
     */
    public static final double ZIPF_EXPONENT = 1.0;

    /**
     * Compact constructor rejecting shapes that cannot produce contention.
     */
    public SwarmShape {
        Objects.requireNonNull(distribution, "The distribution cannot be null.");
        if (writers < 1) {
            throw new IllegalArgumentException("A swarm needs at least one writer, but had " + writers + ".");
        }
        if (accounts < 2) {
            throw new IllegalArgumentException("A transfer needs two accounts, but the swarm had " + accounts + ".");
        }
        if (minBatch < 1 || maxBatch < minBatch) {
            throw new IllegalArgumentException(
                    "The batch range [" + minBatch + "," + maxBatch + "] is not a usable range.");
        }
        if (overlapDegree < 1 || overlapDegree > accounts) {
            throw new IllegalArgumentException(
                    "The overlapDegree must be in [1," + accounts + "], but was " + overlapDegree + ".");
        }
    }

    /**
     * Derives a shape from a seed, letting the seed choose the access distribution too.
     *
     * @param seed the seed
     * @return the shape
     */
    public static SwarmShape of(long seed) {
        Random random = new Random(seed);
        return derive(seed, random, random.nextBoolean() ? Distribution.ZIPF : Distribution.UNIFORM);
    }

    /**
     * Derives a shape from a seed with the access distribution pinned to Zipf.
     * <p>
     * Used by scenarios whose claim is about the conflict path, where a uniform arm would spend most of its budget not
     * producing conflicts at all.
     *
     * @param seed the seed
     * @return the shape, with a hot-key access distribution
     */
    public static SwarmShape zipf(long seed) {
        return derive(seed, new Random(seed), Distribution.ZIPF);
    }

    private static SwarmShape derive(long seed, Random random, Distribution distribution) {
        int writers = WRITER_CHOICES.get(random.nextInt(WRITER_CHOICES.size()));
        int accounts = 6 + random.nextInt(19);
        int overlapDegree = 2 + random.nextInt(Math.max(1, Math.min(accounts, 6) - 1));
        int minBatch = 1 + random.nextInt(2);
        int maxBatch = minBatch + random.nextInt(5);
        return new SwarmShape(seed, writers, accounts, distribution, overlapDegree, minBatch, maxBatch);
    }

    /**
     * Picks an account index for the given writer.
     * <p>
     * The first {@link #overlapDegree()} accounts are the hot set every writer shares; the rest are picked from the
     * whole range so that a run also exercises the uncontended path.
     *
     * @param random the writer's own random source
     * @return an account index in {@code [0, accounts)}
     */
    public int pickAccount(Random random) {
        Objects.requireNonNull(random, "The random cannot be null.");
        return distribution == Distribution.ZIPF ? zipfIndex(random) : random.nextInt(accounts);
    }

    /**
     * Picks the number of transfers a writer issues back to back.
     *
     * @param random the writer's own random source
     * @return a batch size in {@code [minBatch, maxBatch]}
     */
    public int pickBatch(Random random) {
        Objects.requireNonNull(random, "The random cannot be null.");
        return minBatch + random.nextInt(maxBatch - minBatch + 1);
    }

    /**
     * Renders the shape for the history header.
     *
     * @return every knob, rendered flat
     */
    public Map<String, String> describe() {
        Map<String, String> described = new LinkedHashMap<>();
        described.put("swarmSeed", String.valueOf(seed));
        described.put("writers", String.valueOf(writers));
        described.put("accounts", String.valueOf(accounts));
        described.put("distribution", distribution.name());
        described.put("zipfExponent", distribution == Distribution.ZIPF ? String.valueOf(ZIPF_EXPONENT) : "n/a");
        described.put("overlapDegree", String.valueOf(overlapDegree));
        described.put("batchRange", minBatch + ".." + maxBatch);
        return Map.copyOf(described);
    }

    private int zipfIndex(Random random) {
        // Inverse-transform sampling of a Zipf distribution with exponent one over the whole account range, so the
        // hot set is genuinely hot rather than merely more likely.
        double harmonic = 0.0;
        for (int rank = 1; rank <= accounts; rank++) {
            harmonic += 1.0 / Math.pow(rank, ZIPF_EXPONENT);
        }
        double target = random.nextDouble() * harmonic;
        double cumulative = 0.0;
        for (int rank = 1; rank <= accounts; rank++) {
            cumulative += 1.0 / Math.pow(rank, ZIPF_EXPONENT);
            if (cumulative >= target) {
                return rank - 1;
            }
        }
        return accounts - 1;
    }

    /**
     * How a writer picks the accounts it touches.
     *
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public enum Distribution {

        /**
         * Every account is equally likely. Produces very few conflicts, and is the control against which a hot-key
         * run's conflict rate is read.
         */
        UNIFORM,

        /**
         * Rank-proportional: the first account is touched twice as often as the second, three times as often as the
         * third, and so on. This is where the conflict paths live.
         */
        ZIPF
    }
}
