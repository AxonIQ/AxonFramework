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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The timings a run is driven at.
 * <p>
 * The bugs worth hunting live in ratios, not in absolute durations: a batch that outlives its claim, a transaction
 * that outlives a gap timeout, a stall that outlives an extension threshold. Compressing every framework timeout by
 * the same factor preserves the ratios and turns an hour-scale race into a second-scale one, which is the only way a
 * suite ever visits it.
 * <p>
 * <b>What can actually be compressed.</b> {@link #tokenClaimInterval()} and {@link #claimExtensionThreshold()} are
 * processor settings and compress cleanly. The token store's claim timeout is a store setting, and does not exist at
 * all on the in-memory store, so it is carried here for the layers that have one rather than applied at this one. Two
 * durations do <em>not</em> compress and no configuration makes them: a segment split blocks re-claim for a hardcoded
 * sixty seconds, and the coordinator's idle re-poll is a hardcoded five hundred milliseconds. A scenario that splits
 * segments must budget for the former in wall-clock time whatever this record says.
 *
 * <p>
 * <b>The clock-skew allowance is declared here, and it is zero unless a run says otherwise.</b> Whether two nodes
 * may appear to hold one segment at once is decided by comparing timestamps one node wrote against another node's
 * reading of the clock, so an ownership oracle needs a stated tolerance. Making it a field of the timings, recorded
 * in the history header and read back by the checker, is what stops it becoming a silent fudge factor inside the
 * check. Nothing in this suite emulates skew yet, so both arms declare zero.
 *
 * @param name                    the arm's name, recorded in the history header
 * @param tokenClaimInterval      how often the coordinator tries to claim segments it does not hold
 * @param claimExtensionThreshold how long a work package may go without extending its claim
 * @param tokenStoreClaimTimeout  how long a claim survives without extension, for stores that implement ownership
 * @param gapTimeout              how long a gap in a global sequence stays in a tracking token
 * @param stall                   how long a pause fault stalls a participant; longer than every timeout above
 * @param quiescence              the longest a run waits for the system to go quiet before judging it
 * @param ownershipSkewAllowance  how far two nodes' clocks are allowed to disagree before an ownership overlap
 *                                stops being evidence; zero unless the run deliberately emulates skew
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record HuntTimescale(String name,
                            Duration tokenClaimInterval,
                            Duration claimExtensionThreshold,
                            Duration tokenStoreClaimTimeout,
                            Duration gapTimeout,
                            Duration stall,
                            Duration quiescence,
                            Duration ownershipSkewAllowance) {

    /**
     * Compact constructor rejecting a missing name and any non-positive duration.
     */
    public HuntTimescale {
        Objects.requireNonNull(name, "The name cannot be null.");
        requirePositive(tokenClaimInterval, "tokenClaimInterval");
        requirePositive(claimExtensionThreshold, "claimExtensionThreshold");
        requirePositive(tokenStoreClaimTimeout, "tokenStoreClaimTimeout");
        requirePositive(gapTimeout, "gapTimeout");
        requirePositive(stall, "stall");
        requirePositive(quiescence, "quiescence");
        Objects.requireNonNull(ownershipSkewAllowance, "The ownershipSkewAllowance cannot be null.");
        if (ownershipSkewAllowance.isNegative()) {
            throw new IllegalArgumentException(
                    "The ownershipSkewAllowance cannot be negative, but was " + ownershipSkewAllowance + ".");
        }
    }

    /**
     * Returns this arm with a longer token-store claim timeout, for a run whose store is a real database.
     * <p>
     * A hundred-millisecond claim timeout is fine against a store that answers in nanoseconds and hopeless against
     * one that answers over a JDBC round trip: claims expire while their owner is waiting for the extension it
     * already issued, and every node spends the run stealing from every other. The ratio to the extension threshold
     * is what the compression exists to preserve, so widening the timeout without widening the threshold would
     * change the experiment.
     *
     * @param claimTimeout      how long a claim survives without extension
     * @param extensionThreshold how long a work package may go without extending its claim
     * @return the arm, with the two claim timings replaced
     */
    public HuntTimescale withClaimTimings(Duration claimTimeout, Duration extensionThreshold) {
        return new HuntTimescale(name, tokenClaimInterval, extensionThreshold, claimTimeout, gapTimeout, stall,
                                 quiescence, ownershipSkewAllowance);
    }

    /**
     * The default arm: every framework timeout scaled to milliseconds, with the ratios of the shipped defaults kept.
     * <p>
     * The shipped defaults are a five-second claim interval, a five-second extension threshold, a ten-second store
     * claim timeout and a sixty-second gap timeout. Dividing all four by one hundred keeps 1 : 1 : 2 : 12 and puts the
     * whole race inside a second.
     *
     * @return the compressed arm
     */
    public static HuntTimescale compressed() {
        return new HuntTimescale("compressed",
                                 Duration.ofMillis(50),
                                 Duration.ofMillis(50),
                                 Duration.ofMillis(100),
                                 Duration.ofMillis(600),
                                 Duration.ofMillis(300),
                                 Duration.ofSeconds(30),
                                 Duration.ZERO);
    }

    /**
     * The arm that runs at the framework's shipped defaults, for confirming that a compressed finding is not an
     * artefact of compression.
     *
     * @return the realistic arm
     */
    public static HuntTimescale realistic() {
        return new HuntTimescale("realistic",
                                 Duration.ofSeconds(5),
                                 Duration.ofSeconds(5),
                                 Duration.ofSeconds(10),
                                 Duration.ofSeconds(60),
                                 Duration.ofSeconds(30),
                                 Duration.ofMinutes(5),
                                 Duration.ZERO);
    }

    /**
     * Returns the arm with the given name.
     *
     * @param name {@code compressed} or {@code realistic}
     * @return the named arm
     * @throws IllegalArgumentException if the name is neither
     */
    public static HuntTimescale byName(String name) {
        Objects.requireNonNull(name, "The name cannot be null.");
        return switch (name) {
            case "compressed" -> compressed();
            case "realistic" -> realistic();
            default -> throw new IllegalArgumentException(
                    "Unknown timescale [" + name + "]; expected compressed or realistic.");
        };
    }

    /**
     * Renders the arm for the history header.
     *
     * @return the timings, rendered flat in milliseconds
     */
    public Map<String, String> describe() {
        Map<String, String> described = new LinkedHashMap<>();
        described.put("timescale", name);
        described.put("tokenClaimIntervalMs", String.valueOf(tokenClaimInterval.toMillis()));
        described.put("claimExtensionThresholdMs", String.valueOf(claimExtensionThreshold.toMillis()));
        described.put("tokenStoreClaimTimeoutMs", String.valueOf(tokenStoreClaimTimeout.toMillis()));
        described.put("gapTimeoutMs", String.valueOf(gapTimeout.toMillis()));
        described.put("stallMs", String.valueOf(stall.toMillis()));
        described.put("quiescenceMs", String.valueOf(quiescence.toMillis()));
        described.put("ownershipSkewAllowanceMs", String.valueOf(ownershipSkewAllowance.toMillis()));
        return Map.copyOf(described);
    }

    private static void requirePositive(Duration duration, String field) {
        Objects.requireNonNull(duration, "The " + field + " cannot be null.");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("The " + field + " must be positive, but was " + duration + ".");
        }
    }
}
