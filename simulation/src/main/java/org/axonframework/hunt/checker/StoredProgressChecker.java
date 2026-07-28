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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks that the progress a node told the store about matches the work it really did.
 * <p>
 * Three invariants, because durable progress can go wrong in three unrelated ways.
 * <ul>
 *     <li>{@value #STORED_TOKEN_NEVER_REGRESSES}: <em>For every segment, each token stored for it reports a position at
 *     or beyond the position of the token stored for it before, unless the framework itself flagged that store as part
 *     of a replay or the segment had been merged since.</em></li>
 *     <li>{@value #STORED_TOKEN_COVERS_DELIVERED_EVENTS}: <em>For every segment, the last token stored for it reports a
 *     position at or beyond the position of every event that segment delivered.</em></li>
 *     <li>{@value #CLAIM_HANDOVER_REWINDS_AT_MOST_ONE_BATCH}: <em>When a segment's token is claimed again, the events
 *     already delivered from that segment that the stored token does not cover are the events of at most one
 *     batch.</em></li>
 * </ul>
 * <b>Why the third one is the interesting one.</b> A batch's handler effects and that cycle's token progress are
 * persisted in one transaction, and the only externally visible consequence of that is what happens when somebody reads
 * the stored token back. The node taking a segment over resumes from whatever the store holds, so it redelivers exactly
 * the events between that token and wherever the previous holder had got to. Under the one-transaction guarantee those
 * are the events of the one batch the previous holder was interrupted in the middle of, and no more: every batch it had
 * finished stored its progress as part of finishing. Break the guarantee -- commit the effects without the progress --
 * and the work package carries on regardless, because it keeps its position in memory; nothing at all is observable
 * until the token is read back, at which point the new holder rewinds to whenever the store was last told anything and
 * repeats everything since.
 * <p>
 * <b>The quantity checked is what the handover rewinds, not what it then redelivers.</b> The two differ, and the first
 * is the honest one: whether the new holder gets round to redelivering the rewound events before the run ends is a matter
 * of timing, while how far the stored token had fallen behind the effects already applied is a fact about the transaction
 * boundary and is true the moment the claim is granted. Measuring the rewind therefore catches the broken guarantee even
 * on a run that ends immediately afterwards. The redeliveries are counted too, and reported.
 * <p>
 * <b>The bound is the run's declared batch size, not a tolerance.</b> It travels in the history header, so a run with
 * bigger batches licenses proportionally more and neither number can drift from the other.
 * <p>
 * <b>What is deliberately not checked: the other direction.</b> A stored token legitimately reports a position far
 * beyond anything its segment ever handled, because a work package advances its position over every event it reads,
 * including the ones belonging to other segments. So "the token never claims more progress than the effects that are
 * visible" cannot be checked at position granularity and is not: the loss it would catch is caught instead by
 * {@link DeliveryChecker}, which compares the delivered set against the store's own scan and needs no per-segment
 * arithmetic to do it.
 * <p>
 * Two situations make this checker report rather than decide, and both are the standing rules: a run whose read side
 * had not caught up when it ended was interrupted rather than incomplete, and a run in which a fault made the store
 * hold something other than what was offered lost data the harness destroyed. A replayed delivery is never counted as a
 * repeat: the framework flags it as a replay on the delivery itself, and a replay is a redelivery of the whole stream by
 * design.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class StoredProgressChecker implements Checker {

    /**
     * The stable name of the invariant forbidding durable progress from going backwards.
     */
    public static final String STORED_TOKEN_NEVER_REGRESSES = "StoredTokenNeverRegresses";

    /**
     * The statement of {@value #STORED_TOKEN_NEVER_REGRESSES}, character-identical to the invariant registry.
     */
    public static final String STORED_TOKEN_NEVER_REGRESSES_STATEMENT =
            "For every segment, each token stored for it reports a position at or beyond the position of the token "
                    + "stored for it before, unless the framework itself flagged that store as part of a replay or the "
                    + "segment had been merged since.";

    /**
     * The stable name of the invariant requiring durable progress to account for the work already done.
     */
    public static final String STORED_TOKEN_COVERS_DELIVERED_EVENTS = "StoredTokenCoversDeliveredEvents";

    /**
     * The statement of {@value #STORED_TOKEN_COVERS_DELIVERED_EVENTS}, character-identical to the invariant registry.
     */
    public static final String STORED_TOKEN_COVERS_DELIVERED_EVENTS_STATEMENT =
            "For every segment, the last token stored for it reports a position at or beyond the position of every "
                    + "event that segment delivered.";

    /**
     * The stable name of the invariant bounding what a claim handover may repeat.
     */
    public static final String CLAIM_HANDOVER_REWINDS_AT_MOST_ONE_BATCH = "ClaimHandoverRewindsAtMostOneBatch";

    /**
     * The statement of {@value #CLAIM_HANDOVER_REWINDS_AT_MOST_ONE_BATCH}, character-identical to the invariant
     * registry.
     */
    public static final String CLAIM_HANDOVER_REWINDS_AT_MOST_ONE_BATCH_STATEMENT =
            "When a segment's token is claimed again, the events already delivered from that segment that the stored "
                    + "token does not cover are the events of at most one batch.";

    /**
     * The header field naming how many events one batch of the run's projection may hold.
     */
    public static final String BATCH_SIZE = "projectionBatchSize";

    @Override
    public String name() {
        return "StoredProgressChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(STORED_TOKEN_NEVER_REGRESSES,
                      STORED_TOKEN_COVERS_DELIVERED_EVENTS,
                      CLAIM_HANDOVER_REWINDS_AT_MOST_ONE_BATCH);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<Operation> writes = storedTokens(history);
        if (writes.isEmpty()) {
            return CheckResult.holding(name());
        }
        List<Violation> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        checkMonotonicity(history, writes, violations);

        List<String> reasonsToReport = reasonsNotToDecide(history);
        List<Delivery> deliveries = deliveries(history);
        if (deliveries.isEmpty()) {
            return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes));
        }
        if (reasonsToReport.isEmpty()) {
            checkCoverage(history, writes, deliveries, violations);
            checkHandoverRewind(history, writes, deliveries, violations, notes);
        } else {
            notes.add("Durable progress was not judged against the deliveries because "
                              + String.join(" and ", reasonsToReport) + ".");
        }
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes));
    }

    private void checkMonotonicity(HistoryView history, List<Operation> writes, List<Violation> violations) {
        List<Operation> rebuilds = new ArrayList<>(history.operations(HistoryOps.MERGE));
        rebuilds.addAll(history.operations(HistoryOps.SPLIT));
        Map<String, Operation> highest = new LinkedHashMap<>();
        for (Operation write : writes) {
            String segment = write.invocation().key();
            // Only a write the store accepted counts. A node that has lost its claim keeps its position in memory and
            // goes on offering it, and the store refuses those writes on the owner clause of its own update statement --
            // which is the claim protocol working, not progress going backwards. Judging the attempt rather than the
            // outcome reported a 426-position regression on this suite's own first run that the store had already
            // rejected.
            if (segment == null || write.outcome() != Outcome.OK) {
                continue;
            }
            Operation previous = highest.get(segment);
            long position = write.invocation().longValue(HistoryOps.POSITION, -1L);
            // A replay rewinds on purpose, and the framework says so on the token it writes. Treating that as a
            // regression would make every legitimate reset a violation, and would do it four times over -- once per
            // segment.
            // Two things rewind a stored token legitimately, and both are the framework saying so rather than a
            // tolerance. A replay carries its own flag. A merge gives the merged segment the lower of the two halves'
            // tokens, so the surviving segment identifier goes backwards by design.
            boolean rewinding = Boolean.parseBoolean(write.invocation().stringValue(HistoryOps.REPLAY))
                    || segmentSetRebuiltBetween(rebuilds, previous, write);
            if (previous != null && !rewinding) {
                long before = previous.invocation().longValue(HistoryOps.POSITION, -1L);
                if (position < before) {
                    violations.add(Violation.of(
                            STORED_TOKEN_NEVER_REGRESSES,
                            STORED_TOKEN_NEVER_REGRESSES_STATEMENT,
                            "segment [" + segment + "] had a token at position " + before
                                    + " stored, then one at position " + position,
                            List.of(previous.invocation(), write.invocation()),
                            history.header()));
                    continue;
                }
            }
            highest.put(segment, write);
        }
    }

    /**
     * Indicates whether the run rebuilt its segment set between two of a segment's token writes.
     * <p>
     * <b>Deliberately not attributed to the segment the instruction names.</b> A merge is asked for by one segment
     * identifier and the segment that survives it is the sibling with the lower identifier, which the instruction does
     * not carry; the survivor then inherits the lower of the two halves' tokens, so the identifier whose stored token
     * goes backwards is frequently not the one the instruction named. A split deletes and recreates rows the same way.
     * Widening the licence to any rebuild in the window is the honest reading of what the run knows, and it errs towards
     * missing a regression rather than towards inventing one.
     */
    private static boolean segmentSetRebuiltBetween(List<Operation> rebuilds,
                                                    @Nullable Operation previous,
                                                    Operation write) {
        if (previous == null) {
            return false;
        }
        long from = previous.invocation().logicalTs();
        long to = write.invocation().logicalTs();
        // The whole span of the instruction counts, not the instant it was issued. A merge takes hundreds of
        // milliseconds -- it waits for the work packages holding both halves to abort -- so an instruction issued before
        // the earlier of two token writes still takes effect between them. Measured on this suite: a merge issued at
        // 2787ms and completed at 2942ms rewound a token written at 2826ms, and matching on the issue instant alone
        // reported it as a regression.
        return rebuilds.stream().anyMatch(rebuild -> {
            HistoryRecord completion = rebuild.completion();
            long ends = completion == null ? Long.MAX_VALUE : completion.logicalTs();
            return ends >= from && rebuild.invocation().logicalTs() <= to;
        });
    }

    private void checkCoverage(HistoryView history,
                               List<Operation> writes,
                               List<Delivery> deliveries,
                               List<Violation> violations) {
        Map<String, Operation> last = new LinkedHashMap<>();
        for (Operation write : writes) {
            if (write.invocation().key() != null && write.outcome() == Outcome.OK) {
                last.put(write.invocation().key(), write);
            }
        }
        Map<Integer, Delivery> furthest = new LinkedHashMap<>();
        for (Delivery delivery : deliveries) {
            if (delivery.segment() == null || delivery.position() < 0 || delivery.replay()) {
                continue;
            }
            Delivery current = furthest.get(delivery.segment());
            if (current == null || delivery.position() > current.position()) {
                furthest.put(delivery.segment(), delivery);
            }
        }
        furthest.forEach((segment, delivery) -> {
            Operation write = last.entrySet().stream()
                                  .filter(entry -> entry.getKey().endsWith("/" + segment))
                                  .map(Map.Entry::getValue)
                                  .findFirst()
                                  .orElse(null);
            if (write == null) {
                violations.add(Violation.of(
                        STORED_TOKEN_COVERS_DELIVERED_EVENTS,
                        STORED_TOKEN_COVERS_DELIVERED_EVENTS_STATEMENT,
                        "segment [" + segment + "] delivered an event at position " + delivery.position()
                                + " and no token was ever stored for it",
                        List.of(delivery.record()),
                        history.header()));
                return;
            }
            long stored = write.invocation().longValue(HistoryOps.POSITION, -1L);
            if (stored < delivery.position()) {
                violations.add(Violation.of(
                        STORED_TOKEN_COVERS_DELIVERED_EVENTS,
                        STORED_TOKEN_COVERS_DELIVERED_EVENTS_STATEMENT,
                        "segment [" + segment + "] delivered an event at position " + delivery.position()
                                + " but the last token stored for it reports position " + stored,
                        List.of(delivery.record(), write.invocation()),
                        history.header()));
            }
        });
    }

    private void checkHandoverRewind(HistoryView history,
                                     List<Operation> writes,
                                     List<Delivery> deliveries,
                                     List<Violation> violations,
                                     List<String> notes) {
        long batchSize = batchSize(history);
        if (batchSize <= 0) {
            notes.add("The run recorded no batch size, so what a claim handover may legitimately rewind is unknown.");
            return;
        }
        List<Handover> handovers = handovers(history);
        if (handovers.isEmpty()) {
            return;
        }
        Map<Integer, Integer> rewindPerHandover = new TreeMap<>();
        Map<Integer, Integer> repeatsPerHandover = new TreeMap<>();
        for (Handover handover : handovers) {
            long stored = storedPositionBefore(writes, handover);
            Set<String> deliveredBefore = new LinkedHashSet<>();
            Set<String> rewound = new LinkedHashSet<>();
            Set<String> repeated = new LinkedHashSet<>();
            HistoryRecord evidence = null;
            for (Delivery delivery : deliveries) {
                if (delivery.segment() == null || delivery.segment() != handover.segment() || delivery.replay()) {
                    continue;
                }
                if (delivery.at() < handover.at()) {
                    deliveredBefore.add(delivery.eventId());
                    if (delivery.position() > stored) {
                        rewound.add(delivery.eventId());
                        evidence = delivery.record();
                    }
                } else if (delivery.at() <= handover.until() && deliveredBefore.contains(delivery.eventId())) {
                    repeated.add(delivery.eventId());
                }
            }
            rewindPerHandover.merge(rewound.size(), 1, Integer::sum);
            repeatsPerHandover.merge(repeated.size(), 1, Integer::sum);
            if (rewound.size() > batchSize) {
                violations.add(Violation.of(
                        CLAIM_HANDOVER_REWINDS_AT_MOST_ONE_BATCH,
                        CLAIM_HANDOVER_REWINDS_AT_MOST_ONE_BATCH_STATEMENT,
                        "segment [" + handover.segment() + "] was claimed by " + handover.node()
                                + " while the stored token reported position " + stored + ", leaving " + rewound.size()
                                + " event(s) already delivered from it uncovered, which is more than the run's batch of "
                                + batchSize,
                        evidence == null ? List.of(handover.record()) : List.of(handover.record(), evidence),
                        history.header()));
            }
        }
        // Reported only when there is something to report. A handover that inherited every finished batch and repeated
        // nothing is the guarantee working, and a note on every such run would leave every cluster arm permanently
        // undecided -- which would cost the three-valued verdict its meaning. A handover that did rewind or repeat is a
        // fact somebody should look at even where the deployment permits it, so it downgrades the run.
        boolean somethingHappened = rewindPerHandover.keySet().stream().anyMatch(count -> count > 0)
                || repeatsPerHandover.keySet().stream().anyMatch(count -> count > 0);
        if (somethingHappened) {
            notes.add("Claim handovers, by how many already-delivered events the stored token left uncovered: "
                              + rewindPerHandover + ", and by how many the segment then delivered again: "
                              + repeatsPerHandover + "; the run's batch holds " + batchSize + ".");
        }
    }

    /**
     * Returns the largest number of already-delivered events any one claim handover found the stored token not covering.
     * <p>
     * Exposed so that a scenario can assert its handover really landed in the middle of a batch rather than between two
     * of them. A handover with nothing uncovered is an orderly one and proves nothing about what a stale token costs.
     *
     * @param history the history to measure
     * @return the widest rewind across the history's handovers, or {@code 0} when there is none
     */
    public static int widestHandoverRewind(HistoryView history) {
        List<Operation> writes = history.operations(HistoryOps.STORE_TOKEN);
        List<Delivery> deliveries = deliveries(history);
        int widest = 0;
        for (Handover handover : handovers(history)) {
            long stored = storedPositionBefore(writes, handover);
            Set<String> rewound = new LinkedHashSet<>();
            for (Delivery delivery : deliveries) {
                if (delivery.segment() != null && delivery.segment() == handover.segment() && !delivery.replay()
                        && delivery.at() < handover.at() && delivery.position() > stored) {
                    rewound.add(delivery.eventId());
                }
            }
            widest = Math.max(widest, rewound.size());
        }
        return widest;
    }

    /**
     * Returns the furthest position the store had durably accepted for a handover's segment before it happened.
     */
    private static long storedPositionBefore(List<Operation> writes, Handover handover) {
        String suffix = "/" + handover.segment();
        long stored = -1L;
        for (Operation write : writes) {
            HistoryRecord completion = write.completion();
            if (write.outcome() != Outcome.OK || completion == null || write.invocation().key() == null
                    || !write.invocation().key().endsWith(suffix) || completion.logicalTs() >= handover.at()) {
                continue;
            }
            stored = Math.max(stored, write.invocation().longValue(HistoryOps.POSITION, -1L));
        }
        return stored;
    }

    /**
     * Returns the largest number of events any one claim handover made a segment deliver again.
     * <p>
     * Exposed so that a scenario can print what a handover really costs, and so that the bound this checker enforces
     * can be justified from measurement rather than from a number somebody liked. Returns {@code 0} when the history
     * holds no handover.
     *
     * @param history the history to measure
     * @return the largest repeat count across the history's claim handovers
     */
    public static int widestHandoverRepeat(HistoryView history) {
        List<Delivery> deliveries = deliveries(history);
        int widest = 0;
        for (Handover handover : handovers(history)) {
            Set<String> before = new LinkedHashSet<>();
            Set<String> repeated = new LinkedHashSet<>();
            for (Delivery delivery : deliveries) {
                if (delivery.segment() == null || delivery.segment() != handover.segment() || delivery.replay()) {
                    continue;
                }
                if (delivery.at() < handover.at()) {
                    before.add(delivery.eventId());
                } else if (delivery.at() <= handover.until() && before.contains(delivery.eventId())) {
                    repeated.add(delivery.eventId());
                }
            }
            widest = Math.max(widest, repeated.size());
        }
        return widest;
    }

    /**
     * Returns every point at which a segment's token was claimed again after having been claimed before.
     * <p>
     * <b>A re-claim by the same node counts, and that is deliberate.</b> What matters is that the stored token was read
     * back, and it is read back on every claim: a node coming back from a crash, a node re-taking a segment it released
     * because it held too many, and a node stealing a segment from somebody else are the same question with the same
     * answer. Restricting this to a change of owner would have missed the crash-and-restart case entirely, which on this
     * suite's own runs is where the redeliveries actually happen.
     * <p>
     * A window runs from the grant to one claim timeout later, which is the longest the previous holder can still be
     * draining the batch it was interrupted in.
     *
     * @param history the history to read
     * @return the re-claims, in the order they were granted
     */
    public static List<Handover> handovers(HistoryView history) {
        Long claimTimeout = millisField(history, OwnershipChecker.CLAIM_TIMEOUT_MS);
        if (claimTimeout == null) {
            return List.of();
        }
        List<Handover> handovers = new ArrayList<>();
        Set<String> claimedBefore = new LinkedHashSet<>();
        for (Operation claim : history.operations(HistoryOps.CLAIM)) {
            HistoryRecord invocation = claim.invocation();
            Integer segment = segmentOf(invocation);
            if (segment == null || claim.outcome() != Outcome.OK || claim.completion() == null) {
                continue;
            }
            String key = invocation.key() == null ? String.valueOf(segment) : invocation.key();
            if (!claimedBefore.add(key)) {
                long at = claim.completion().logicalTs();
                handovers.add(new Handover(segment, String.valueOf(invocation.node()), at, at + claimTimeout,
                                           claim.completion()));
            }
        }
        return List.copyOf(handovers);
    }

    private static List<Operation> storedTokens(HistoryView history) {
        return history.operations(HistoryOps.STORE_TOKEN);
    }

    private static List<Delivery> deliveries(HistoryView history) {
        List<Delivery> deliveries = new ArrayList<>();
        for (Operation delivery : history.operations(HistoryOps.DELIVER)) {
            HistoryRecord record = delivery.invocation();
            String eventId = record.stringValue(DcbHistoryCodec.EVENT_ID);
            if (eventId == null) {
                continue;
            }
            Object rawSegment = record.value().get(HistoryOps.SEGMENT);
            Integer segment = rawSegment instanceof Number number ? number.intValue() : null;
            deliveries.add(new Delivery(eventId,
                                        segment,
                                        record.longValue(HistoryOps.POSITION, -1L),
                                        Boolean.parseBoolean(record.stringValue(HistoryOps.REPLAY)),
                                        record.logicalTs(),
                                        record));
        }
        return List.copyOf(deliveries);
    }

    private static @Nullable Integer segmentOf(HistoryRecord record) {
        Object raw = record.value().get(HistoryOps.SEGMENT);
        return raw instanceof Number number ? number.intValue() : null;
    }

    private static long batchSize(HistoryView history) {
        Long declared = longField(history, BATCH_SIZE);
        return declared == null ? -1L : declared;
    }

    private List<String> reasonsNotToDecide(HistoryView history) {
        List<String> reasons = new ArrayList<>();
        Boolean quiesced = null;
        for (HistoryRecord phase : history.notes(HistoryOps.PHASE)) {
            String recorded = phase.stringValue(HistoryOps.QUIESCED);
            if (recorded != null) {
                quiesced = Boolean.parseBoolean(recorded);
            }
        }
        if (quiesced == null) {
            reasons.add("the run never recorded whether its read side caught up");
        } else if (!quiesced) {
            reasons.add("the read side had not caught up when the run ended");
        }
        if (!history.notes(HistoryOps.STORE_PERTURBED).isEmpty()) {
            reasons.add("a fault made the store hold something other than what was offered");
        }
        return reasons;
    }

    private static @Nullable Long millisField(HistoryView history, String field) {
        Long value = longField(history, field);
        return value == null ? null : value * 1_000_000L;
    }

    private static @Nullable Long longField(HistoryView history, String field) {
        String value = history.header().workloadShape().get(field);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * One segment's claim moving from one node to another.
     *
     * @param segment the segment that changed hands
     * @param node    the node that took it
     * @param at      when the store granted it, on the recorder's logical clock
     * @param until   the end of the window in which the losing node may still be draining its interrupted batch
     * @param record  the record granting the claim
     * @author Stefan Dragisic
     * @since 5.3.0
     */
    public record Handover(int segment, String node, long at, long until, HistoryRecord record) {

    }

    private record Delivery(String eventId,
                            @Nullable Integer segment,
                            long position,
                            boolean replay,
                            long at,
                            HistoryRecord record) {

    }
}
