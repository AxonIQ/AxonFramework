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

package org.axonframework.hunt.checker;

import org.axonframework.hunt.history.HistoryOps;
import org.axonframework.hunt.history.HistoryRecord;
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.history.Operation;
import org.axonframework.hunt.history.Outcome;
import org.axonframework.hunt.model.DcbHistoryCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks that the read side received every committed event, and received none of them twice where it may not.
 * <p>
 * Two invariants, because losing an event and repeating one are different failures with different verdicts.
 * <ul>
 *     <li>{@value #NO_COMMITTED_EVENT_GOES_UNDELIVERED}: <em>Every event a committed append made visible is delivered
 *     to a consumer at least once.</em> Loss is a hard failure under every delivery mode; nothing licenses it.</li>
 *     <li>{@value #DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW}: <em>An event is delivered more than once only
 *     while a recorded claim transition, segment-count change or node recovery window is open, or as part of a replay
 *     the delivery itself reports, and never at all when the run declares exactly-once delivery.</em></li>
 * </ul>
 * <b>The mode is declared by the scenario and never inferred.</b> Exactly-once holds when the token store and the read
 * model are one transactional resource and does not hold when they are two; a checker that guessed would be inventing
 * the guarantee it exists to verify. The mode travels in the history header, so a recorded run is re-judged under the
 * mode it actually ran in.
 * <p>
 * <b>A repeat is licensed by a rewind the history recorded, and bounded by the position that rewind went back to.</b>
 * Every redelivery the framework can legitimately produce has the same single cause: a node was told to resume from a
 * position behind the work its segment had already done. A stolen claim leaves the interrupted batch's progress
 * unstored; a node coming back re-reads whatever its token last held; a merge hands the surviving segment the lower of
 * the two halves' tokens; a split hands its children a reconciled position; a reset rewinds every segment to the
 * beginning. Four instructions, one mechanism -- and the mechanism is observable, because the store answers every claim
 * with the token the node is to resume from.
 * <p>
 * A granted claim therefore opens a licence when, and only when, the position it hands back is behind an event that
 * segment had already delivered. The licence is bounded twice over: in <b>position</b>, to the events above the
 * position resumed from, which are exactly the events the rewind un-did; and in <b>time</b>, to one claim timeout,
 * which is the longest the losing node can still be draining the batch it was interrupted in. Both bounds are widened
 * by the run's declared clock-skew allowance. Deriving the licence from elapsed time alone -- which an earlier version
 * of this checker did -- forgives every repeat that happens to fall inside a window, including ones the rewind cannot
 * explain.
 * <p>
 * <b>A replay is the same mechanism with no time bound, because the framework says so on the token.</b> After a reset
 * the whole stream is redelivered on purpose and for as long as it takes, so a window would be meaningless; instead the
 * framework marks each such delivery through the token it hands the handler, and it records the position the reset
 * rewound from. A replayed repeat is accounted for when the framework called it a replay <em>and</em> it sits at or
 * below that position. A run that resets and then repeats an event above it, or one the framework did not call a
 * replay, is still a failure.
 * <p>
 * <b>A rebuild widens the licence's segment scope and nothing else.</b> A split deletes one token row and creates two;
 * a merge deletes one of a pair and rewrites the other with the lower of their tokens. The segment that had already
 * delivered an event may therefore no longer exist by the time the rewind is granted, so on a run that rebuilt its
 * segments a rewind licenses positions above it whatever segment the repeat arrives from. Measured on this suite:
 * segment 7 delivered an event at position 2962, segments 7 and 3 merged into 3, and the claim on segment 3 came back
 * at position 2952 -- one claim carrying the whole rewind, for a segment that had delivered nothing above it. The
 * position bound is what does the work here, and it is untouched.
 * <p>
 * <b>An accounted repeat is a measurement, not a note.</b> It is printed with its distribution and the verdict stands:
 * the history explains every one of them, so there is nothing undecided. A repeat that falls inside a recovery window
 * but that no recorded rewind explains -- which is what a delivery carrying no segment or no position leaves behind --
 * is reported as a note and does downgrade the run, because that one really is unexplained. An earlier version reported
 * every repeat as a note, which left the replay and membership arms permanently undecided: an arm that can never reach
 * a pass can never signal a regression either.
 * <p>
 * Three situations make this checker report rather than decide, and each one exists because deciding would be wrong:
 * a run whose read side was still moving when the run ended has not lost anything, it was interrupted; a run in
 * which a fault made the store hold something other than what was offered lost data the harness destroyed; and a
 * history from before the run recorded whether it caught up cannot be judged either way, so it is left alone.
 * <p>
 * <b>Still moving is the operative phrase, and it is what makes the first of those a guard rather than a blindfold.</b>
 * A store that loses an event permanently leaves the read side behind for ever, which is the same observation an
 * interrupted run produces, so a checker that declined on "did not catch up" alone declined on the very defect it
 * exists to find -- measured, on a mutation of the gap-aware token that skips an index and never comes back. The run
 * therefore reports whether the read side had <em>stopped</em>: a drain that ends with the delivery count unchanged for
 * longer than the run's stall window has nothing in flight, and loss is decided on it exactly as it would be on a run
 * that caught up.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class DeliveryChecker implements Checker {

    /**
     * The stable name of the invariant forbidding event loss on the read side.
     */
    public static final String NO_COMMITTED_EVENT_GOES_UNDELIVERED = "NoCommittedEventGoesUndelivered";

    /**
     * The statement of {@value #NO_COMMITTED_EVENT_GOES_UNDELIVERED}, character-identical to the invariant registry.
     */
    public static final String NO_COMMITTED_EVENT_GOES_UNDELIVERED_STATEMENT =
            "Every event a committed append made visible is delivered to a consumer at least once.";

    /**
     * The stable name of the invariant bounding when an event may be delivered twice.
     */
    public static final String DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW =
            "DuplicateDeliveryOnlyInsideRecoveryWindow";

    /**
     * The statement of {@value #DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW}, character-identical to the invariant
     * registry.
     */
    public static final String DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW_STATEMENT =
            "An event is delivered more than once only while a recorded claim transition, segment-count change or node "
                    + "recovery window is open, or as part of a replay the delivery itself reports, and never at all "
                    + "when the run declares exactly-once delivery.";

    /**
     * The header field naming the delivery guarantee the run's deployment can provide.
     */
    public static final String DELIVERY_MODE = "deliveryMode";

    /**
     * The value of {@link #DELIVERY_MODE} under which no repeated delivery is permitted at all.
     */
    public static final String EXACTLY_ONCE = "EXACTLY_ONCE";

    private static final long NANOS_PER_MILLI = 1_000_000L;

    /**
     * The segment a rewind licence matches when the run rebuilt its segment set and a segment identifier therefore
     * stops naming one unit of work. The position bound still applies; only the segment scope widens.
     */
    private static final int ANY_SEGMENT = Integer.MIN_VALUE;

    @Override
    public String name() {
        return "DeliveryChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(NO_COMMITTED_EVENT_GOES_UNDELIVERED, DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<Operation> deliveries = history.operations(HistoryOps.DELIVER);
        Set<String> committed = committedEvents(history);
        if (deliveries.isEmpty() && committed.isEmpty()) {
            return CheckResult.holding(name());
        }
        Boolean quiesced = quiesced(history);
        if (quiesced == null) {
            return CheckResult.holding(name());
        }

        List<String> reasonsToReport = new ArrayList<>();
        // A run that rebuilt its segment set cannot be held to a stall window at all. The framework blocks local re-claim
        // of a segment it has just split for a hardcoded sixty seconds, which no timescale compresses and no arm's settle
        // budget can outlast, so a segment waiting on that block is indistinguishable from one nothing will ever come
        // back for -- measured, as two events reported lost on one seed of the split-and-merge storm while the other seed
        // delivered everything. Declining is the honest answer; raising every membership arm's budget past a minute is
        // not.
        boolean stalled = Boolean.TRUE.equals(flag(history, HistoryOps.STALLED)) && !history.rebuiltSegments();
        if (!quiesced && !stalled) {
            reasonsToReport.add("the read side had not caught up when the run ended");
        }
        if (!history.notes(HistoryOps.STORE_PERTURBED).isEmpty()) {
            reasonsToReport.add("a fault made the store hold something other than what was offered");
        }
        boolean decide = reasonsToReport.isEmpty();

        Map<String, List<HistoryRecord>> perEvent = new LinkedHashMap<>();
        for (Operation delivery : deliveries) {
            String eventId = delivery.invocation().stringValue(DcbHistoryCodec.EVENT_ID);
            if (eventId != null) {
                perEvent.computeIfAbsent(eventId, key -> new ArrayList<>()).add(delivery.invocation());
            }
        }

        List<Violation> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> measurements = new ArrayList<>();

        Set<String> lost = new LinkedHashSet<>(committed);
        lost.removeAll(perEvent.keySet());
        if (!lost.isEmpty()) {
            String detail = lost.size() + " committed event(s) never reached a consumer, for example "
                    + lost.stream().limit(5).toList()
                    + (stalled && !quiesced
                    ? "; the read side had stopped accepting deliveries altogether, so nothing was in flight"
                    : "");
            if (decide) {
                violations.add(Violation.of(NO_COMMITTED_EVENT_GOES_UNDELIVERED,
                                            NO_COMMITTED_EVENT_GOES_UNDELIVERED_STATEMENT,
                                            detail, List.of(), history.header()));
            } else {
                notes.add(detail + "; not judged as loss because " + String.join(" and ", reasonsToReport) + ".");
            }
        }

        Licences licences = Licences.of(history);
        List<Window> windows = recoveryWindows(history);
        boolean exactlyOnce = EXACTLY_ONCE.equals(history.header().workloadShape().get(DELIVERY_MODE));
        Map<Integer, Integer> repeatDistribution = new TreeMap<>();
        int accounted = 0;
        int unexplained = 0;
        int outside = 0;
        for (Map.Entry<String, List<HistoryRecord>> entry : perEvent.entrySet()) {
            List<HistoryRecord> records = entry.getValue();
            if (records.size() < 2) {
                continue;
            }
            repeatDistribution.merge(records.size(), 1, Integer::sum);
            for (HistoryRecord repeat : records.subList(1, records.size())) {
                if (!exactlyOnce && licences.account(repeat)) {
                    accounted++;
                    continue;
                }
                // No recorded rewind explains this one. It is still not a violation if a recovery window was open,
                // because a delivery that carries no segment or no position cannot be held to a position bound; the
                // run is undecided about it rather than broken by it.
                if (!exactlyOnce && windows.stream().anyMatch(window -> window.covers(repeat.logicalTs()))) {
                    unexplained++;
                    continue;
                }
                outside++;
                if (decide) {
                    violations.add(Violation.of(
                            DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW,
                            DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW_STATEMENT,
                            "event " + entry.getKey() + " was delivered again "
                                    + (exactlyOnce
                                    ? "although the run declares exactly-once delivery"
                                    : "with no recorded rewind licensing it and no recovery window open"),
                            List.of(records.getFirst(), repeat),
                            history.header()));
                }
            }
        }
        if (!repeatDistribution.isEmpty()) {
            String distribution = "Repeated deliveries, by how many times an event arrived: " + repeatDistribution
                    + "; " + accounted + " accounted for by " + licences.size() + " recorded rewind(s), " + unexplained
                    + " inside a recovery window that no rewind explains, and " + outside + " with neither.";
            if (!decide) {
                notes.add(distribution + " Not judged because " + String.join(" and ", reasonsToReport) + ".");
            } else if (unexplained > 0) {
                notes.add(distribution);
            } else if (outside == 0) {
                measurements.add(distribution);
            } else {
                // Every one of them is already a violation, so the distribution is context for a verdict that is
                // decided rather than a fact about a verdict that stands.
                notes.add(distribution);
            }
        }
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes), List.copyOf(measurements),
                               List.of());
    }

    /**
     * Returns the events the run is entitled to see delivered.
     * <p>
     * The authoritative scan taken after the run quiesced is the store's own answer and is preferred; a history
     * without one falls back to the events whose commit the run recorded as successful.
     */
    static Set<String> committedEvents(HistoryView history) {
        List<HistoryRecord> scans = history.notes(HistoryOps.SCAN);
        if (!scans.isEmpty()) {
            return new LinkedHashSet<>(scans.getLast().stringListValue(DcbHistoryCodec.EVENT_IDS));
        }
        Set<String> committed = new LinkedHashSet<>();
        for (Operation commit : history.operations(HistoryOps.COMMIT)) {
            if (commit.outcome() == Outcome.OK) {
                committed.addAll(commit.invocation().stringListValue(DcbHistoryCodec.EVENT_IDS));
            }
        }
        return committed;
    }

    private static @org.jspecify.annotations.Nullable Boolean quiesced(HistoryView history) {
        return flag(history, HistoryOps.QUIESCED);
    }

    private static @org.jspecify.annotations.Nullable Boolean flag(HistoryView history, String field) {
        for (HistoryRecord phase : history.notes(HistoryOps.PHASE)) {
            String recorded = phase.stringValue(field);
            if (recorded != null) {
                return Boolean.parseBoolean(recorded);
            }
        }
        return null;
    }

    private static List<Window> recoveryWindows(HistoryView history) {
        Map<String, String> shape = history.header().workloadShape();
        Long claimTimeout = millisField(shape, OwnershipChecker.CLAIM_TIMEOUT_MS);
        Long skew = millisField(shape, OwnershipChecker.SKEW_ALLOWANCE_MS);
        if (claimTimeout == null) {
            return List.of();
        }
        long allowance = skew == null ? 0L : skew;
        List<Window> windows = new ArrayList<>();
        Map<String, String> lastOwner = new HashMap<>();
        for (Operation claim : history.operations(HistoryOps.CLAIM)) {
            HistoryRecord invocation = claim.invocation();
            String segment = invocation.key();
            String node = String.valueOf(invocation.node());
            if (segment == null || claim.outcome() != Outcome.OK || claim.completion() == null) {
                continue;
            }
            String previous = lastOwner.put(segment, node);
            if (previous != null && !previous.equals(node)) {
                long at = claim.completion().logicalTs();
                windows.add(new Window(at - allowance, at + claimTimeout + allowance));
            }
        }
        for (HistoryRecord node : history.notes(HistoryOps.NODE)) {
            String action = node.stringValue(HistoryOps.ACTION);
            if ("crashed".equals(action) || "restarted".equals(action)) {
                windows.add(new Window(node.logicalTs() - allowance, node.logicalTs() + claimTimeout + allowance));
            }
        }
        for (String instruction : List.of(HistoryOps.SPLIT, HistoryOps.MERGE)) {
            for (Operation change : history.operations(instruction)) {
                HistoryRecord completion = change.completion();
                if (completion != null && "true".equals(completion.stringValue(HistoryOps.CARRIED_OUT))) {
                    windows.add(new Window(change.invocation().logicalTs() - allowance,
                                           completion.logicalTs() + claimTimeout + allowance));
                }
            }
        }
        return List.copyOf(windows);
    }

    private static @org.jspecify.annotations.Nullable Long millisField(Map<String, String> shape, String field) {
        String value = shape.get(field);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value) * NANOS_PER_MILLI;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Window(long from, long to) {

        private boolean covers(long at) {
            return at >= from && at <= to;
        }
    }

    /**
     * Every rewind the run recorded, and what each one licenses.
     * <p>
     * Derived entirely from what the store answered: a claim's completion carries the position the node was told to
     * resume from, so a rewind is visible as the store's own answer rather than inferred from anything the harness
     * decided. A claim opens a rewind licence only when that position is behind an event the segment had already
     * delivered -- a first claim of an empty segment rewinds nothing and licenses nothing, which is what keeps a
     * bootstrap from forgiving a genuine duplicate.
     *
     * @param rewinds      one per granted claim that resumed behind work already done
     * @param rewoundTo    the furthest position any recorded reset rewound the processor to, or {@code -1} when none did
     * @param resetAt      when the earliest recorded reset was granted, on the recorder's logical clock
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    private record Licences(List<Rewind> rewinds, long rewoundTo, long resetAt) {

        private static Licences of(HistoryView history) {
            Map<String, String> shape = history.header().workloadShape();
            Long claimTimeout = millisField(shape, OwnershipChecker.CLAIM_TIMEOUT_MS);
            if (claimTimeout == null) {
                return new Licences(List.of(), -1L, Long.MAX_VALUE);
            }
            Long skew = millisField(shape, OwnershipChecker.SKEW_ALLOWANCE_MS);
            long allowance = skew == null ? 0L : skew;
            List<Delivered> delivered = delivered(history);
            // A rebuild renames the unit of work, so a rewind cannot be scoped to the segment that was claimed.
            // Measured on this suite: segment 7 delivered the event at position 2962, segments 3 and 7 then merged into
            // 3, and the claim on 3 came back at position 2952 -- so the rewind is visible, exactly one claim carries
            // it, and the segment that had delivered the event no longer exists. Matching per segment misses it
            // entirely; matching any segment keeps the position bound, which is the part that does the work.
            boolean rebuilt = history.rebuiltSegments();
            List<Rewind> rewinds = new ArrayList<>();
            long rewoundTo = -1L;
            long resetAt = Long.MAX_VALUE;
            for (Operation reset : history.operations(HistoryOps.RESET)) {
                HistoryRecord completion = reset.completion();
                if (reset.outcome() == Outcome.OK && completion != null) {
                    rewoundTo = Math.max(rewoundTo, completion.longValue(HistoryOps.TOKEN_AT_RESET, -1L));
                    resetAt = Math.min(resetAt, reset.invocation().logicalTs() - allowance);
                }
            }
            for (Operation claim : history.operations(HistoryOps.CLAIM)) {
                HistoryRecord completion = claim.completion();
                Integer segment = segmentOf(claim.invocation());
                if (claim.outcome() != Outcome.OK || completion == null || segment == null) {
                    continue;
                }
                long resumedFrom = completion.longValue(HistoryOps.POSITION, Long.MIN_VALUE);
                if (resumedFrom == Long.MIN_VALUE) {
                    // A history recorded before a claim carried the position it granted. Nothing can be bounded by a
                    // number that is not there, so no licence is derived and the repeat falls through to the window.
                    continue;
                }
                long grantedAt = completion.logicalTs();
                if (Boolean.parseBoolean(completion.stringValue(HistoryOps.REPLAY))) {
                    rewoundTo = Math.max(rewoundTo, completion.longValue(HistoryOps.TOKEN_AT_RESET, -1L));
                    resetAt = Math.min(resetAt, grantedAt - allowance);
                }
                long furthest = Long.MIN_VALUE;
                for (Delivered earlier : delivered) {
                    if ((rebuilt || earlier.segment() == segment) && earlier.at() < grantedAt && !earlier.replay()) {
                        furthest = Math.max(furthest, earlier.position());
                    }
                }
                if (furthest > resumedFrom) {
                    rewinds.add(new Rewind(rebuilt ? ANY_SEGMENT : segment, resumedFrom, grantedAt - allowance,
                                           grantedAt + claimTimeout + allowance));
                }
            }
            return new Licences(List.copyOf(rewinds), rewoundTo, resetAt);
        }

        /**
         * Indicates whether a recorded rewind explains this repeat.
         */
        private boolean account(HistoryRecord repeat) {
            Integer segment = segmentOf(repeat);
            long position = repeat.longValue(HistoryOps.POSITION, -1L);
            if (segment == null || position < 0) {
                return false;
            }
            if (Boolean.parseBoolean(repeat.stringValue(HistoryOps.REPLAY))) {
                // A reset is a processor-wide instruction over one stream of positions, so the bound is the position it
                // rewound to and not a per-segment one. It carries no time bound either: a replay redelivers for as
                // long as the replay lasts, and the framework's own flag says when that is.
                return repeat.logicalTs() >= resetAt && position <= rewoundTo;
            }
            return rewinds.stream().anyMatch(rewind -> (rewind.segment() == ANY_SEGMENT
                    || rewind.segment() == segment)
                    && position > rewind.resumedFrom()
                    && repeat.logicalTs() >= rewind.from()
                    && repeat.logicalTs() <= rewind.to());
        }

        private int size() {
            return rewinds.size() + (rewoundTo >= 0 ? 1 : 0);
        }

        private static List<Delivered> delivered(HistoryView history) {
            List<Delivered> deliveries = new ArrayList<>();
            for (Operation delivery : history.operations(HistoryOps.DELIVER)) {
                HistoryRecord record = delivery.invocation();
                Integer segment = segmentOf(record);
                if (segment != null) {
                    deliveries.add(new Delivered(segment,
                                                 record.longValue(HistoryOps.POSITION, -1L),
                                                 Boolean.parseBoolean(record.stringValue(HistoryOps.REPLAY)),
                                                 record.logicalTs()));
                }
            }
            return List.copyOf(deliveries);
        }

        private static @org.jspecify.annotations.Nullable Integer segmentOf(HistoryRecord record) {
            Object raw = record.value().get(HistoryOps.SEGMENT);
            return raw instanceof Number number ? number.intValue() : null;
        }
    }

    private record Rewind(int segment, long resumedFrom, long from, long to) {

    }

    private record Delivered(int segment, long position, boolean replay, long at) {

    }
}
