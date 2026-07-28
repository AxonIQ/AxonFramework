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
 * Checks that no two nodes hold one segment's token claim at the same time.
 * <p>
 * The invariant is {@value #AT_MOST_ONE_SEGMENT_OWNER}: <em>For every segment, the intervals during which distinct
 * nodes hold its token claim never overlap by more than the run's declared clock-skew allowance.</em>
 * <p>
 * <b>Where the intervals come from.</b> Ownership is not directly observable: the owner and the timestamp live in a
 * row nobody queries. What a run can record is which node asked for a claim and whether the store granted it, and the
 * intervals are derived from exactly that. A granted claim opens an interval; a granted extension refreshes it; a
 * release closes it; and, failing all three, it closes when the store's own rule says the claim expired, one claim
 * timeout after the last time its owner refreshed it. The same owner may always re-take its own claim, expired or
 * not, so a grant to a node whose previous interval had already lapsed opens a fresh interval rather than extending
 * the stale one -- treating it as one long interval would manufacture an overlap out of an ordinary re-claim.
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
        return Set.of(AT_MOST_ONE_SEGMENT_OWNER);
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
            return new CheckResult(name(), List.of(),
                                   List.of("The run's token store implements no ownership, so " + nodes.size()
                                                   + " nodes each hold every segment they ask for and "
                                                   + AT_MOST_ONE_SEGMENT_OWNER
                                                   + " cannot be verified against it."));
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
        return new CheckResult(name(), List.copyOf(violations), List.of());
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
            if ((operation.op().equals(HistoryOps.CLAIM)
                    || operation.op().equals(HistoryOps.EXTEND)
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

        private long lapsesAt(long claimTimeoutNanos) {
            return lastRefreshRequestedAt + claimTimeoutNanos;
        }

        private long end(long claimTimeoutNanos) {
            long lapsed = Math.max(start, lapsesAt(claimTimeoutNanos));
            return closedAt < 0 ? lapsed : Math.min(closedAt, lapsed);
        }
    }
}
