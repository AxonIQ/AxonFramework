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

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Holds a transaction open after its rows have been written, so that a reader sees a hole where an event will be.
 * <p>
 * <b>This is the one fault that exists to reach a specific line of framework code, and where the delay sits is the whole
 * of it.</b> The aggregate-based storage engine takes its global index from a database sequence and flushes the rows while
 * it is still being asked to append -- the transaction's own commit does no work at all -- so between the moment an index
 * is taken and the moment the transaction commits, a concurrent reader can see index n+1 while n is a hole that will
 * later fill. Delaying the append would move nothing, because no index has been taken yet; delaying the append
 * transaction's {@code commit()} moves nothing either, because on this store that call does no work and races the
 * database transaction rather than preceding it. The delay belongs strictly between the two, which is what
 * {@link StoreHook#afterAppend(AppendAttempt)} is.
 * <p>
 * A reader that meets such a hole is supposed to record it as a gap and come back for it. Whether it does depends on a
 * comparison the reader makes between the event it can see and the wall clock:
 * {@code allowGaps = timestamp.isAfter(now - gapTimeout)}. An event whose own message timestamp is older than that
 * threshold is read as one whose neighbours are never coming, and no gap is recorded for the hole below it at all -- so
 * when the held transaction finally commits, nobody is looking for it any more.
 * <p>
 * Holding a commit for longer than the gap timeout produces both halves of that at once, which is why one fault is
 * enough: the hole stays open past the timeout <em>and</em> the event that is visible above it has aged past the
 * threshold by the time it is read. Size the hold above the arm's gap timeout and state which one, or the fault opens a
 * hole that is correctly filled and proves nothing.
 * <p>
 * <b>It does not perturb the store's contents, and that matters more than it looks.</b> Every event offered is stored,
 * exactly once, exactly as offered; the only thing that changed is when. So no oracle downgrades itself for this run,
 * and a loss the read side suffers under it is the framework's to answer for -- which is the entire reason this is a
 * delay and not a fault that drops something.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public final class LateCommitFault implements Fault {

    private final Duration hold;
    private final double probability;
    private final long seed;
    private volatile @Nullable StoreHook hook;

    /**
     * Creates the fault.
     *
     * @param hold        how long a held commit stays open after its rows have been written; size it above the arm's gap
     *                    timeout
     * @param probability the chance in {@code [0,1]} that a given commit is held
     * @param seed        the seed fixing which commits are chosen, so an arm's shape is a property of the run rather
     *                    than of the machine it ran on
     */
    public LateCommitFault(Duration hold, double probability, long seed) {
        this.hold = Objects.requireNonNull(hold, "The hold cannot be null.");
        if (hold.isNegative()) {
            throw new IllegalArgumentException("The hold cannot be negative, but was " + hold + ".");
        }
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("The probability must be in [0,1], but was " + probability + ".");
        }
        this.probability = probability;
        this.seed = seed;
    }

    @Override
    public String kind() {
        return "late-commit";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("holdMs", String.valueOf(hold.toMillis()),
                      "probability", String.valueOf(probability),
                      "seed", String.valueOf(seed));
    }

    @Override
    public void activate(FaultSite site, FaultEvidence evidence) {
        StoreHook installed = new StoreHook() {
            @Override
            public void afterAppend(AppendAttempt attempt) {
                if (!chosen(attempt)) {
                    return;
                }
                try {
                    Thread.sleep(hold.toMillis(), hold.toNanosPart() % 1_000_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                evidence.fired(attempt.describe());
            }
        };
        hook = installed;
        site.installStoreHook(installed);
    }

    /**
     * Decides whether this attempt is held, from the attempt's own sequence number rather than from a shared generator.
     * <p>
     * Commits arrive from every writer thread at once, so drawing from one random source would make which append is held
     * a property of the thread schedule. Mixing the attempt's sequence number with the run's seed gives the same answer
     * for the same append however the threads interleave.
     */
    private boolean chosen(AppendAttempt attempt) {
        if (probability >= 1.0) {
            return true;
        }
        long mixed = attempt.sequence() * 0x9E3779B97F4A7C15L + seed;
        mixed ^= mixed >>> 33;
        mixed *= 0xFF51AFD7ED558CCDL;
        mixed ^= mixed >>> 33;
        return (Math.abs(mixed % 1_000_000L) / 1_000_000.0) < probability;
    }

    @Override
    public void deactivate(FaultSite site) {
        StoreHook installed = hook;
        if (installed != null) {
            site.removeStoreHook(installed);
            hook = null;
        }
    }
}
