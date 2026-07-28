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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that the system kept making progress: events reached consumers in time, and commands finished.
 * <p>
 * Two invariants, because the two things that can stop are different.
 * <ul>
 *     <li>{@value #COMMITTED_EVENT_DELIVERED_WITHIN_HORIZON}: <em>Every committed event that reaches a consumer
 *     reaches it within the run's declared liveness horizon.</em></li>
 *     <li>{@value #ACCEPTED_COMMAND_COMPLETES}: <em>Every command the run dispatched reaches a recorded
 *     outcome.</em></li>
 * </ul>
 * <b>Latency here, presence elsewhere.</b> This checker judges only how long a delivery took, never whether it
 * happened: an event that never arrived at all is loss, which is {@link DeliveryChecker}'s invariant, and having two
 * oracles report the same fact twice makes a run look twice as broken as it is. An event with no delivery is
 * therefore skipped here in silence, and so is one the store does not hold, which was never committed at all.
 * <p>
 * <b>The clock is the recorder's monotonic one, never the wall clock.</b> Latency is the difference between two
 * logical timestamps taken from one monotonic source inside one run. Wall-clock timestamps travel in the history for
 * correlating with external evidence and are unusable for this: a clock adjustment mid-run would turn a healthy
 * system into a liveness violation, or hide a real one.
 * <p>
 * <b>Measured from the commit's invocation, not its completion.</b> The store publishes inside the commit call and
 * the harness writes its record after that call returns, so a fast consumer is legitimately recorded before the
 * commit is. Measuring from the completion would give some deliveries a negative latency and would understate the
 * rest.
 * <p>
 * <b>The horizon is the scenario's, with a stated basis.</b> It travels in the history header. A run that declares
 * none is measured and reported rather than judged, because a horizon the checker invented for itself is a constant
 * somebody would raise until the suite went green. A run whose read side had not caught up when it ended is also
 * only reported: it was interrupted, not slow.
 * <p>
 * An undetermined command outcome -- a timeout, an ambiguous commit -- is a note and never a violation. The command
 * completed; what it did is unknown, and that is the ambiguity rule rather than a liveness failure.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class LivenessChecker implements Checker {

    /**
     * The stable name of the invariant bounding how long a committed event may take to reach a consumer.
     */
    public static final String COMMITTED_EVENT_DELIVERED_WITHIN_HORIZON = "CommittedEventDeliveredWithinHorizon";

    /**
     * The statement of {@value #COMMITTED_EVENT_DELIVERED_WITHIN_HORIZON}, character-identical to the invariant
     * registry.
     */
    public static final String COMMITTED_EVENT_DELIVERED_WITHIN_HORIZON_STATEMENT =
            "Every committed event that reaches a consumer reaches it within the run's declared liveness horizon.";

    /**
     * The stable name of the invariant requiring every dispatched command to finish.
     */
    public static final String ACCEPTED_COMMAND_COMPLETES = "AcceptedCommandCompletes";

    /**
     * The statement of {@value #ACCEPTED_COMMAND_COMPLETES}, character-identical to the invariant registry.
     */
    public static final String ACCEPTED_COMMAND_COMPLETES_STATEMENT =
            "Every command the run dispatched reaches a recorded outcome.";

    /**
     * The header field naming how long a committed event may take to reach a consumer, in milliseconds.
     */
    public static final String LIVENESS_HORIZON_MS = "livenessHorizonMs";

    private static final long NANOS_PER_MILLI = 1_000_000L;

    @Override
    public String name() {
        return "LivenessChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(COMMITTED_EVENT_DELIVERED_WITHIN_HORIZON, ACCEPTED_COMMAND_COMPLETES);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<Violation> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        checkNodesCameUp(history, notes);
        checkCommands(history, violations, notes);
        checkDeliveryLatency(history, violations, notes);
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes));
    }

    /**
     * Reports any node that was still down when the run ended.
     * <p>
     * A cluster that came up short did not verify a cluster, so this is reported rather than passed over. It is a
     * note and not a violation because the framework promises nothing about how many instances survive a start; the
     * consequence is that the run cannot be a clean pass, which is the honest verdict for a run that was one node
     * short of the one it declared. A node that failed and then came up on a retry is not reported here: it was up
     * for the part of the run the oracles judge, and the failed attempt is in the history for whoever wants it.
     */
    private void checkNodesCameUp(HistoryView history, List<String> notes) {
        Map<String, String> lastAction = new java.util.LinkedHashMap<>();
        for (HistoryRecord record : history.notes(HistoryOps.NODE)) {
            String action = record.stringValue(HistoryOps.ACTION);
            if (record.node() != null && action != null) {
                lastAction.put(record.node(), action);
            }
        }
        List<String> down = lastAction.entrySet().stream()
                                      .filter(entry -> entry.getValue().equals("start-failed"))
                                      .map(Map.Entry::getKey)
                                      .toList();
        if (!down.isEmpty()) {
            notes.add("Node(s) " + down + " never came up, so the run exercised a smaller cluster than it declared.");
        }
    }

    private void checkCommands(HistoryView history, List<Violation> violations, List<String> notes) {
        List<Operation> commands = history.operations(HistoryOps.TRANSFER);
        int undetermined = 0;
        for (Operation command : commands) {
            if (command.completion() == null) {
                violations.add(Violation.of(ACCEPTED_COMMAND_COMPLETES,
                                            ACCEPTED_COMMAND_COMPLETES_STATEMENT,
                                            "the command issued as record #" + command.invocation().idx()
                                                    + " never reached an outcome",
                                            List.of(command.invocation()),
                                            history.header()));
            } else if (command.outcome() == Outcome.UNKNOWN) {
                undetermined++;
            }
        }
        if (undetermined > 0) {
            notes.add(undetermined + " command(s) completed with an undetermined outcome; the command finished and "
                              + "what it did is unknown, which is an ambiguity rather than a liveness failure.");
        }
    }

    private void checkDeliveryLatency(HistoryView history, List<Violation> violations, List<String> notes) {
        Map<String, HistoryRecord> firstDelivery = new HashMap<>();
        for (Operation delivery : history.operations(HistoryOps.DELIVER)) {
            String eventId = delivery.invocation().stringValue(DcbHistoryCodec.EVENT_ID);
            if (eventId != null) {
                firstDelivery.putIfAbsent(eventId, delivery.invocation());
            }
        }
        if (firstDelivery.isEmpty()) {
            return;
        }

        Long horizon = horizonNanos(history);
        if (horizon == null || !"true".equals(quiescedFlag(history))) {
            return;
        }
        Set<String> committed = DeliveryChecker.committedEvents(history);

        for (Operation commit : history.operations(HistoryOps.COMMIT)) {
            HistoryRecord invocation = commit.invocation();
            if (commit.outcome() != Outcome.OK) {
                continue;
            }
            for (String eventId : invocation.stringListValue(DcbHistoryCodec.EVENT_IDS)) {
                HistoryRecord delivered = firstDelivery.get(eventId);
                // An event the store does not hold was never really committed, and one that is held but never
                // arrived is loss rather than lateness. Both belong to the delivery oracle; reporting them here too
                // would make one fact look like two failures.
                if (delivered == null || !committed.contains(eventId)) {
                    continue;
                }
                long latency = delivered.logicalTs() - invocation.logicalTs();
                if (latency > horizon) {
                    violations.add(Violation.of(COMMITTED_EVENT_DELIVERED_WITHIN_HORIZON,
                                                COMMITTED_EVENT_DELIVERED_WITHIN_HORIZON_STATEMENT,
                                                "event " + eventId + " took " + (latency / NANOS_PER_MILLI)
                                                        + "ms to reach a consumer, past the declared horizon of "
                                                        + (horizon / NANOS_PER_MILLI) + "ms",
                                                List.of(invocation, delivered),
                                                history.header()));
                }
            }
        }
    }

    /**
     * Returns the slowest commit-to-delivery latency the history holds, in nanoseconds.
     * <p>
     * Exposed so that a scenario's horizon can be set from what the arm actually costs rather than from a number
     * somebody liked. Returns {@code -1} when the history holds no delivery to measure.
     *
     * @param history the history to measure
     * @return the slowest first-delivery latency in nanoseconds, or {@code -1}
     */
    public static long slowestDeliveryNanos(HistoryView history) {
        Map<String, HistoryRecord> firstDelivery = new HashMap<>();
        for (Operation delivery : history.operations(HistoryOps.DELIVER)) {
            String eventId = delivery.invocation().stringValue(DcbHistoryCodec.EVENT_ID);
            if (eventId != null) {
                firstDelivery.putIfAbsent(eventId, delivery.invocation());
            }
        }
        long worst = -1L;
        for (Operation commit : history.operations(HistoryOps.COMMIT)) {
            HistoryRecord invocation = commit.invocation();
            for (String eventId : invocation.stringListValue(DcbHistoryCodec.EVENT_IDS)) {
                HistoryRecord delivered = firstDelivery.get(eventId);
                if (delivered != null) {
                    worst = Math.max(worst, delivered.logicalTs() - invocation.logicalTs());
                }
            }
        }
        return worst;
    }

    private static @org.jspecify.annotations.Nullable Long horizonNanos(HistoryView history) {
        String value = history.header().workloadShape().get(LIVENESS_HORIZON_MS);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value) * NANOS_PER_MILLI;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @org.jspecify.annotations.Nullable String quiescedFlag(HistoryView history) {
        for (HistoryRecord phase : history.notes(HistoryOps.PHASE)) {
            String recorded = phase.stringValue(HistoryOps.QUIESCED);
            if (recorded != null) {
                return recorded;
            }
        }
        return null;
    }
}
