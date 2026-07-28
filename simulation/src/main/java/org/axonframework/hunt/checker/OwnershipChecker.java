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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks who owned each segment, and that every effect came from a node entitled to produce it.
 * <p>
 * Two invariants, because holding a claim and acting on one are separate facts.
 * <ul>
 *     <li>{@value #AT_MOST_ONE_SEGMENT_OWNER}: <em>For every segment, the intervals during which distinct nodes hold
 *     its token claim never overlap by more than the run's declared clock-skew allowance.</em></li>
 *     <li>{@value #DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER}: <em>Every event a node delivers from a segment is delivered
 *     while that node holds the segment's claim, or within one claim timeout of losing it.</em></li>
 * </ul>
 * <b>Where the intervals come from.</b> Ownership is not directly observable: the owner and the timestamp live in a
 * row nobody queries. What a run can record is which node asked for a claim and whether the store granted it, and the
 * intervals are derived from exactly that. A granted claim opens an interval; a granted extension or token write
 * refreshes it, because the store refreshes the row's timestamp in the same statement that writes the token; a release
 * closes it; and, failing all four, it closes when the store's own rule says the claim expired, one claim timeout after
 * the last time its owner refreshed it. The same owner may always re-take its own claim, expired or not, so a grant to
 * a node whose previous interval had already lapsed opens a fresh interval rather than extending the stale one --
 * treating it as one long interval would manufacture an overlap out of an ordinary re-claim.
 * <p>
 * <b>Every interval is deliberately the smallest one the records can justify.</b> It starts when the store answered,
 * not when the node asked, and it expires from when the node asked, not from when the store answered. The claim was
 * really granted somewhere between the two, so the narrow reading can only ever under-report an overlap. That is the
 * right direction to be wrong in: a checker that occasionally misses a violation costs a run, and a checker that
 * invents one costs a week.
 * <p>
 * <b>The clock-skew allowance is declared by the run, never assumed here.</b> Expiry is decided by comparing a
 * timestamp one node wrote against another node's reading of the clock, so an oracle needs a stated tolerance. It is
 * read from the run's timescale, recorded in the history header, and is zero unless the run deliberately emulated
 * skew. A tolerance the checker chose for itself would be a fudge factor with a comment.
 * <p>
 * <b>A store that arbitrates nothing gets a note, not a pass.</b> The framework's in-heap token store has no owner
 * field, no timestamp and no expiry, so it grants every claim to everybody and every ownership assertion made against
 * it holds without checking anything. When the run declares such a store and more than one node claimed against it,
 * this checker says so rather than reporting a verified invariant. A single-node run is silent: there is nothing to
 * arbitrate.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class OwnershipChecker implements Checker {

    /**
     * The stable name of the invariant requiring one owner per segment.
     */
    public static final String AT_MOST_ONE_SEGMENT_OWNER = "AtMostOneSegmentOwner";

    /**
     * The stable name of the invariant tying a delivery to the node entitled to make it.
     */
    public static final String DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER = "DeliveryAttributedToSegmentOwner";

    /**
     * The statement of {@value #DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER}, character-identical to the invariant registry.
     */
    public static final String DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER_STATEMENT =
            "Every event a node delivers from a segment is delivered while that node holds the segment's claim, or "
                    + "within one claim timeout of losing it.";

    /**
     * The statement of {@value #AT_MOST_ONE_SEGMENT_OWNER}, character-identical to the invariant registry.
     */
    public static final String AT_MOST_ONE_SEGMENT_OWNER_STATEMENT =
            "For every segment, the intervals during which distinct nodes hold its token claim never overlap by more "
                    + "than the run's declared clock-skew allowance.";

    /**
     * The header field naming whether the run's token store decides who owns a segment.
     */
    public static final String ARBITRATES_CLAIMS = "tokenStoreArbitratesClaims";

    /**
     * The header field naming how long a claim survives without extension, in milliseconds.
     */
    public static final String CLAIM_TIMEOUT_MS = "tokenStoreClaimTimeoutMs";

    /**
     * The header field naming how far two nodes' clocks may disagree before an overlap stops being evidence, in
     * milliseconds.
     */
    public static final String SKEW_ALLOWANCE_MS = "ownershipSkewAllowanceMs";

    private static final long NANOS_PER_MILLI = 1_000_000L;

    @Override
    public String name() {
        return "OwnershipChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(AT_MOST_ONE_SEGMENT_OWNER, DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<Operation> claims = ownershipOperations(history);
        if (claims.isEmpty()) {
            return CheckResult.holding(name());
        }
        Set<String> nodes = new LinkedHashSet<>();
        claims.forEach(claim -> nodes.add(String.valueOf(claim.invocation().node())));
        if (nodes.size() < 2) {
            // One node cannot contend with itself, whatever the store does.
            return CheckResult.holding(name());
        }

        Map<String, String> shape = history.header().workloadShape();
        String arbitrates = shape.get(ARBITRATES_CLAIMS);
        if (arbitrates == null) {
            return CheckResult.holding(name());
        }
        if (!Boolean.parseBoolean(arbitrates)) {
            // Not undecidedness: this store has no owner to arbitrate, so the invariant has nothing to be true or
            // false about here. Reporting it as a note would say the run tried and could not tell; reporting nothing
            // would say it passed. Naming it is the only reading that is neither.
            return CheckResult.notApplicable(name(),
                                             List.of(AT_MOST_ONE_SEGMENT_OWNER + " and "
                                                             + DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER
                                                             + " are not expressible on this run: its token store "
                                                             + "implements no ownership, so each of the "
                                                             + nodes.size()
                                                             + " nodes holds every segment it asks for."));
        }
        Long claimTimeoutNanos = millisField(shape, CLAIM_TIMEOUT_MS);
        Long skewNanos = millisField(shape, SKEW_ALLOWANCE_MS);
        if (claimTimeoutNanos == null || skewNanos == null) {
            return new CheckResult(name(), List.of(),
                                   List.of("The run recorded no claim timeout or no clock-skew allowance, so the "
                                                   + "point at which a claim lapses is unknown and ownership cannot "
                                                   + "be judged."));
        }

        Map<String, List<Interval>> perSegment = new LinkedHashMap<>();
        for (Operation operation : claims) {
            apply(perSegment, operation, claimTimeoutNanos);
        }
        closeAtSegmentSetRebuilds(perSegment, segmentSetRebuilds(history));

        List<Violation> violations = new ArrayList<>();
        perSegment.forEach((segment, intervals) -> {
            for (int first = 0; first < intervals.size(); first++) {
                for (int second = first + 1; second < intervals.size(); second++) {
                    Interval left = intervals.get(first);
                    Interval right = intervals.get(second);
                    if (left.node().equals(right.node())) {
                        continue;
                    }
                    long overlap = Math.min(left.end(claimTimeoutNanos), right.end(claimTimeoutNanos))
                            - Math.max(left.start(), right.start());
                    if (overlap > skewNanos) {
                        violations.add(Violation.of(
                                AT_MOST_ONE_SEGMENT_OWNER,
                                AT_MOST_ONE_SEGMENT_OWNER_STATEMENT,
                                "segment [" + segment + "] was held by " + left.node() + " and " + right.node()
                                        + " at the same time for " + (overlap / NANOS_PER_MILLI) + "ms, which is more "
                                        + "than the declared clock-skew allowance of " + (skewNanos / NANOS_PER_MILLI)
                                        + "ms",
                                List.of(left.granted(), right.granted()),
                                history.header()));
                    }
                }
            }
        });
        List<String> notes = new ArrayList<>();
        List<String> notApplicable = new ArrayList<>();
        checkAttribution(history, perSegment, claimTimeoutNanos, skewNanos, violations, notes, notApplicable);
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes), List.of(),
                               List.copyOf(notApplicable));
    }

    /**
     * Checks that every delivery came from a node entitled to make it.
     * <p>
     * The framework says a node that loses a claim fails its next token write and rolls the batch back, and it says
     * nothing at all about how long the de-claimed node may keep executing before it discovers the loss. So a delivery
     * from a node that no longer holds the segment is permitted within one claim timeout of losing it -- the window in
     * which it may still be draining the batch it was interrupted in -- and is a violation outside that window, where
     * nothing licenses it. The window is widened by the run's declared clock-skew allowance at both ends, for the same
     * reason the overlap check tolerates it.
     * <p>
     * A delivery that names no segment or no node is skipped rather than guessed at: the segment and the node are the
     * framework's own, read back off the processing context it built, and a checker that invented either would be
     * judging itself.
     */
    private static void checkAttribution(HistoryView history,
                                         Map<String, List<Interval>> perSegment,
                                         long claimTimeoutNanos,
                                         long skewNanos,
                                         List<Violation> violations,
                                         List<String> notes,
                                         List<String> notApplicable) {
        if (!segmentSetRebuilds(history).isEmpty()) {
            // A split deletes one token row and creates two; a merge deletes one of a pair and rewrites the other. The
            // segment a delivery names therefore does not identify the same unit of work before and after, and no
            // ownership interval derived from claim traffic can follow it across the change. Reporting that is the only
            // honest answer; guessing which side of a rebuild a delivery belongs to would make the verdict a property of
            // the guess.
            //
            // It is a scoping statement rather than undecidedness, and the difference is what makes a membership arm
            // usable at all: this run can never express the invariant, so a note would leave every such arm permanently
            // undecided and unable to signal a regression, while silence would let the gap read as coverage.
            notApplicable.add(DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER + " is not expressible on this run: it rebuilt its "
                                      + "segment set, and a segment identifier does not name the same unit of work "
                                      + "either side of a split or a merge.");
            return;
        }
        int outside = 0;
        int judged = 0;
        for (Operation delivery : history.operations(HistoryOps.DELIVER)) {
            HistoryRecord record = delivery.invocation();
            Object rawSegment = record.value().get(HistoryOps.SEGMENT);
            if (record.node() == null || !(rawSegment instanceof Number number)) {
                continue;
            }
            List<Interval> intervals = perSegment.get(segmentKey(perSegment, number.intValue()));
            if (intervals == null) {
                continue;
            }
            judged++;
            boolean entitled = intervals.stream()
                                        .filter(interval -> interval.node().equals(record.node()))
                                        .anyMatch(interval -> record.logicalTs() >= interval.start() - skewNanos
                                                && record.logicalTs()
                                                <= interval.end(claimTimeoutNanos) + claimTimeoutNanos + skewNanos);
            if (!entitled) {
                outside++;
                violations.add(Violation.of(
                        DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER,
                        DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER_STATEMENT,
                        "node " + record.node() + " delivered an event from segment " + number.intValue()
                                + " while holding no claim on it, and outside every window in which it could still "
                                + "have been draining an interrupted batch",
                        List.of(record),
                        history.header()));
            }
        }
        if (judged == 0) {
            notes.add("No delivery recorded both the node that made it and the segment it came from, so "
                              + DELIVERY_ATTRIBUTED_TO_SEGMENT_OWNER + " judged nothing.");
        } else if (outside > 0) {
            notes.add(outside + " of " + judged + " attributable deliveries came from a node holding no claim.");
        }
    }

    private static String segmentKey(Map<String, List<Interval>> perSegment, int segmentId) {
        String suffix = "/" + segmentId;
        for (String key : perSegment.keySet()) {
            if (key.endsWith(suffix)) {
                return key;
            }
        }
        return suffix;
    }

    /**
     * Returns the longest any two distinct nodes appeared to hold one segment at the same time, in milliseconds.
     * <p>
     * Exposed so that an arm which is expected to break ownership can report how badly rather than merely that it did.
     * A number is the only useful answer to "how much clock skew does the claim protocol tolerate", which the framework
     * does not state. Returns {@code -1} when the history holds no two nodes' claims on one segment to compare.
     *
     * @param history the history to measure
     * @return the widest overlap in milliseconds, or {@code -1} when there is nothing to measure
     */
    public static long widestOverlapMillis(HistoryView history) {
        Long claimTimeoutNanos = millisField(history.header().workloadShape(), CLAIM_TIMEOUT_MS);
        if (claimTimeoutNanos == null) {
            return -1L;
        }
        Map<String, List<Interval>> perSegment = new LinkedHashMap<>();
        for (Operation operation : ownershipOperations(history)) {
            apply(perSegment, operation, claimTimeoutNanos);
        }
        long widest = -1L;
        for (List<Interval> intervals : perSegment.values()) {
            for (int first = 0; first < intervals.size(); first++) {
                for (int second = first + 1; second < intervals.size(); second++) {
                    Interval left = intervals.get(first);
                    Interval right = intervals.get(second);
                    if (left.node().equals(right.node())) {
                        continue;
                    }
                    widest = Math.max(widest,
                                      Math.min(left.end(claimTimeoutNanos), right.end(claimTimeoutNanos))
                                              - Math.max(left.start(), right.start()));
                }
            }
        }
        return widest < 0 ? widest : widest / NANOS_PER_MILLI;
    }

    /**
     * Returns the instants at which the run rebuilt its segment set, from the recorder's own monotonic clock.
     * <p>
     * A split deletes a token row and creates two, and a merge deletes one of a pair and rewrites the other. Neither
     * appears as a claim or a release, so an interval derived from claim traffic alone runs straight through a segment
     * that no longer exists in the form it was claimed in -- and the next node to claim the rebuilt row looks like a
     * second simultaneous owner. Ending every open interval at a rebuild is what stops that reading a membership change
     * as a broken claim.
     */
    private static List<Long> segmentSetRebuilds(HistoryView history) {
        List<Long> instants = new ArrayList<>();
        for (String instruction : List.of(HistoryOps.SPLIT, HistoryOps.MERGE)) {
            for (Operation change : history.operations(instruction)) {
                HistoryRecord completion = change.completion();
                if (completion != null && "true".equals(completion.stringValue(HistoryOps.CARRIED_OUT))) {
                    instants.add(change.invocation().logicalTs());
                }
            }
        }
        java.util.Collections.sort(instants);
        return instants;
    }

    private static void closeAtSegmentSetRebuilds(Map<String, List<Interval>> perSegment, List<Long> rebuilds) {
        if (rebuilds.isEmpty()) {
            return;
        }
        perSegment.values().forEach(intervals -> intervals.forEach(interval -> rebuilds.stream()
                                                                                      .filter(at -> at > interval.start())
                                                                                      .findFirst()
                                                                                      .ifPresent(interval::closeNoLaterThan)));
    }

    private static void apply(Map<String, List<Interval>> perSegment, Operation operation, long claimTimeoutNanos) {
        HistoryRecord invocation = operation.invocation();
        String segment = invocation.key();
        String node = String.valueOf(invocation.node());
        if (segment == null) {
            return;
        }
        List<Interval> intervals = perSegment.computeIfAbsent(segment, key -> new ArrayList<>());
        Interval open = intervals.stream()
                                 .filter(interval -> interval.node().equals(node) && interval.isOpen())
                                 .reduce((first, second) -> second)
                                 .orElse(null);
        if (operation.op().equals(HistoryOps.RELEASE)) {
            if (open != null && operation.completion() != null) {
                open.close(operation.completion().logicalTs());
            }
            return;
        }
        if (operation.outcome() != Outcome.OK || operation.completion() == null) {
            return;
        }
        if (open != null && open.lapsesAt(claimTimeoutNanos) >= invocation.logicalTs()) {
            open.refresh(invocation.logicalTs());
            return;
        }
        intervals.add(new Interval(node, operation.completion().logicalTs(), invocation.logicalTs(),
                                   operation.completion()));
    }

    private static List<Operation> ownershipOperations(HistoryView history) {
        List<Operation> operations = new ArrayList<>();
        for (Operation operation : history.operations()) {
            // Storing a token refreshes the claim's timestamp in the same statement that writes the token, under a
            // WHERE clause on the owner, so a successful store is a claim refresh and an interval derived without it
            // lapses while the store's own row is fresh. Leaving it out would manufacture overlaps out of nothing more
            // than a busy work package, which extends rarely precisely because every store already refreshed it.
            if ((operation.op().equals(HistoryOps.CLAIM)
                    || operation.op().equals(HistoryOps.EXTEND)
                    || operation.op().equals(HistoryOps.STORE_TOKEN)
                    || operation.op().equals(HistoryOps.RELEASE))
                    && operation.invocation().node() != null) {
                operations.add(operation);
            }
        }
        return operations;
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

    /**
     * One node's hold on one segment, from the moment the store granted it to the moment it lapsed or was given back.
     */
    private static final class Interval {

        private final String node;
        private final long start;
        private final HistoryRecord granted;
        private long lastRefreshRequestedAt;
        private long closedAt = -1L;

        private Interval(String node, long start, long lastRefreshRequestedAt, HistoryRecord granted) {
            this.node = node;
            this.start = start;
            this.lastRefreshRequestedAt = lastRefreshRequestedAt;
            this.granted = granted;
        }

        private String node() {
            return node;
        }

        private long start() {
            return start;
        }

        private HistoryRecord granted() {
            return granted;
        }

        private boolean isOpen() {
            return closedAt < 0;
        }

        private void refresh(long requestedAt) {
            lastRefreshRequestedAt = Math.max(lastRefreshRequestedAt, requestedAt);
        }

        private void close(long at) {
            closedAt = at;
        }

        private void closeNoLaterThan(long at) {
            closedAt = closedAt < 0 ? at : Math.min(closedAt, at);
        }

        private long lapsesAt(long claimTimeoutNanos) {
            return lastRefreshRequestedAt + claimTimeoutNanos;
        }

        private long end(long claimTimeoutNanos) {
            long lapsed = Math.max(start, lapsesAt(claimTimeoutNanos));
            return closedAt < 0 ? lapsed : Math.min(closedAt, lapsed);
        }
    }
}
