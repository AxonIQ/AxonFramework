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
 *     while a recorded claim transition or node recovery window is open, and never at all when the run declares
 *     exactly-once delivery.</em></li>
 * </ul>
 * <b>The mode is declared by the scenario and never inferred.</b> Exactly-once holds when the token store and the read
 * model are one transactional resource and does not hold when they are two; a checker that guessed would be inventing
 * the guarantee it exists to verify. The mode travels in the history header, so a recorded run is re-judged under the
 * mode it actually ran in.
 * <p>
 * <b>A repeat is permitted only where the framework says it may happen.</b> A stolen claim makes the previous owner's
 * token update fail and its batch roll back, so effects already applied are applied again; a node coming back
 * re-processes whatever its token had not yet advanced past. A window therefore opens at every recorded change of a
 * segment's owner and at every recorded node crash or restart, and closes one claim timeout later, widened at both
 * ends by the run's declared clock-skew allowance. A repeat inside such a window is expected and is reported as a
 * distribution. A repeat outside every window is a failure: nothing was happening that licenses it.
 * <p>
 * <b>Permitted repeats still stop a run being a clean pass.</b> They are reported, not violated, and reporting them
 * downgrades the run to undecided, because a projection that applied a transfer twice is a fact somebody should look
 * at even when the deployment permits it. A checker that stayed silent about them would be the reason nobody ever did.
 * <p>
 * Three situations make this checker report rather than decide, and each one exists because deciding would be wrong:
 * a run whose read side had not caught up when the run ended has not lost anything, it was interrupted; a run in
 * which a fault made the store hold something other than what was offered lost data the harness destroyed; and a
 * history from before the run recorded whether it caught up cannot be judged either way, so it is left alone.
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
            "An event is delivered more than once only while a recorded claim transition or node recovery window is "
                    + "open, and never at all when the run declares exactly-once delivery.";

    /**
     * The header field naming the delivery guarantee the run's deployment can provide.
     */
    public static final String DELIVERY_MODE = "deliveryMode";

    /**
     * The value of {@link #DELIVERY_MODE} under which no repeated delivery is permitted at all.
     */
    public static final String EXACTLY_ONCE = "EXACTLY_ONCE";

    private static final long NANOS_PER_MILLI = 1_000_000L;

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
        if (!quiesced) {
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

        Set<String> lost = new LinkedHashSet<>(committed);
        lost.removeAll(perEvent.keySet());
        if (!lost.isEmpty()) {
            String detail = lost.size() + " committed event(s) never reached a consumer, for example "
                    + lost.stream().limit(5).toList();
            if (decide) {
                violations.add(Violation.of(NO_COMMITTED_EVENT_GOES_UNDELIVERED,
                                            NO_COMMITTED_EVENT_GOES_UNDELIVERED_STATEMENT,
                                            detail, List.of(), history.header()));
            } else {
                notes.add(detail + "; not judged as loss because " + String.join(" and ", reasonsToReport) + ".");
            }
        }

        List<Window> windows = recoveryWindows(history);
        boolean exactlyOnce = EXACTLY_ONCE.equals(history.header().workloadShape().get(DELIVERY_MODE));
        Map<Integer, Integer> repeatDistribution = new TreeMap<>();
        int inside = 0;
        int outside = 0;
        for (Map.Entry<String, List<HistoryRecord>> entry : perEvent.entrySet()) {
            List<HistoryRecord> records = entry.getValue();
            if (records.size() < 2) {
                continue;
            }
            repeatDistribution.merge(records.size(), 1, Integer::sum);
            for (HistoryRecord repeat : records.subList(1, records.size())) {
                boolean licensed = !exactlyOnce && windows.stream().anyMatch(window -> window.covers(repeat.logicalTs()));
                if (licensed) {
                    inside++;
                } else {
                    outside++;
                    if (decide) {
                        violations.add(Violation.of(
                                DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW,
                                DUPLICATE_DELIVERY_ONLY_INSIDE_RECOVERY_WINDOW_STATEMENT,
                                "event " + entry.getKey() + " was delivered again "
                                        + (exactlyOnce
                                        ? "although the run declares exactly-once delivery"
                                        : "while no claim transition or node recovery window was open"),
                                List.of(records.getFirst(), repeat),
                                history.header()));
                    }
                }
            }
        }
        if (!repeatDistribution.isEmpty()) {
            notes.add("Repeated deliveries, by how many times an event arrived: " + repeatDistribution + "; " + inside
                              + " repeat(s) inside a recovery window and " + outside + " outside one, across "
                              + windows.size() + " recorded window(s)."
                              + (decide ? "" : " Not judged because " + String.join(" and ", reasonsToReport) + "."));
        }
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes));
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
        for (HistoryRecord phase : history.notes(HistoryOps.PHASE)) {
            String recorded = phase.stringValue(HistoryOps.QUIESCED);
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
}
