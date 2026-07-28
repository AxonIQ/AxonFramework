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
import org.axonframework.hunt.model.ModelEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks that an append the client was told had succeeded is really in the store.
 * <p>
 * <b>This is the only invariant in the suite that is about the store and not about the framework's decisions.</b> Every
 * other oracle asks whether the framework did the right thing with what it was given; this one asks whether the thing the
 * framework told the caller had happened actually happened, by putting the question to the store afterwards. It is
 * therefore also the only oracle whose answer can differ between a map in the heap and a database that was killed
 * mid-write, which is exactly why it exists.
 * <p>
 * <b>Three outcomes, and only two of them are decidable.</b> The client's own view of an append is one of:
 * <ul>
 *     <li><b>succeeded</b> -- the append completed normally. Its events must be in the store, exactly once. Absent is
 *     lost data the caller believes it has; twice is a duplicate the caller never asked for.</li>
 *     <li><b>rejected</b> -- the store, or a fault standing in for it, decided against the append. Handled by
 *     {@link AppendOutcomeChecker#REJECTED_APPEND_LEAVES_NO_EVENTS}, not here.</li>
 *     <li><b>unknown</b> -- the append failed for a reason that is not a decision: a dropped connection, a store that
 *     stopped answering, a call that never returned at all. The request may have been applied and the reply lost, so
 *     <em>either</em> answer from the store is correct and nothing may be concluded about this append.</li>
 * </ul>
 * <b>A failure is not a decision unless the store said so.</b> An append that failed with the framework's own rejection,
 * or with the harness's own injected refusal, is a decision and the store is bound by it. Anything else -- a driver
 * exception, a pool exception, a timeout -- is a lost conversation, and treating it as a rejection is how a partition
 * scenario invents findings: the commit may well have been applied on the far side of the broken socket.
 * <p>
 * <b>A run with no unknowns has not tested this.</b> Under a fault that cuts the connection, an append that neither
 * succeeded nor was decided against is the whole point; a run that produced none of them measured a system nothing
 * happened to, whatever it reports. The count is therefore always published, and a run that declared such a fault and
 * produced zero unknowns says so and gives up its pass. That is the difference between "durability held" and "the
 * nemesis never reached the commit window".
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class DurabilityChecker implements Checker {

    /**
     * The stable name of the invariant that an acknowledged append is really in the store.
     */
    public static final String ACKNOWLEDGED_APPEND_IS_DURABLE = "AcknowledgedAppendIsDurable";

    /**
     * The statement of {@link #ACKNOWLEDGED_APPEND_IS_DURABLE}, character-identical to the invariant registry.
     */
    public static final String ACKNOWLEDGED_APPEND_IS_DURABLE_STATEMENT =
            "Every event of an append the client saw succeed is present exactly once in the authoritative scan of the "
                    + "store.";

    /**
     * The fault kinds whose whole purpose is to make an acknowledgement ambiguous.
     * <p>
     * A run that declared one of these and produced no ambiguous append at all did not reach the window the claim is
     * about, so its verdict is downgraded however clean it looks.
     */
    private static final Set<String> AMBIGUITY_MAKING_FAULTS = Set.of("store-partition", "store-crash", "store-freeze");

    @Override
    public String name() {
        return "DurabilityChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(ACKNOWLEDGED_APPEND_IS_DURABLE);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<Operation> appends = history.operations(HistoryOps.APPEND);
        List<HistoryRecord> scans = history.notes(HistoryOps.SCAN);
        if (appends.isEmpty() || scans.isEmpty()) {
            return CheckResult.holding(name());
        }
        if (!history.notes(HistoryOps.STORE_PERTURBED).isEmpty()) {
            // The harness itself made the store hold something other than what was offered -- a batch vanished, doubled or
            // truncated behind the workload's back. Every acknowledgement in such a run is one the harness falsified, so
            // reporting the difference as the store failing to keep what it said it kept is reporting the harness's own
            // work with a very convincing-looking message attached. A store killed or cut off is a different matter and
            // is decided: nothing there was offered dishonestly.
            return CheckResult.notApplicable(name(), List.of(
                    ACKNOWLEDGED_APPEND_IS_DURABLE + " is not expressible on this run: a fault made the store hold "
                            + "something other than what was offered, so an acknowledgement the harness falsified cannot "
                            + "be held against the store."));
        }

        Map<String, Integer> timesStored = new LinkedHashMap<>();
        for (String identifier : scans.getLast().stringListValue(DcbHistoryCodec.EVENT_IDS)) {
            timesStored.merge(identifier, 1, Integer::sum);
        }

        List<Violation> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> measurements = new ArrayList<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        int acknowledged = 0;

        for (Operation append : appends) {
            List<String> identifiers = eventIdentifiersOf(append);
            if (identifiers.isEmpty()) {
                continue;
            }
            if (append.outcome() == Outcome.OK) {
                acknowledged++;
                List<String> missing = identifiers.stream().filter(id -> !timesStored.containsKey(id)).toList();
                List<String> repeated = identifiers.stream().filter(id -> timesStored.getOrDefault(id, 0) > 1).toList();
                if (!missing.isEmpty() || !repeated.isEmpty()) {
                    violations.add(Violation.of(ACKNOWLEDGED_APPEND_IS_DURABLE,
                                                ACKNOWLEDGED_APPEND_IS_DURABLE_STATEMENT,
                                                describe(missing, repeated, timesStored),
                                                append.records(),
                                                history.header()));
                }
            } else if (!decided(append)) {
                ambiguous.addAll(identifiers);
            }
        }

        measurements.add("Client verdicts on " + appends.size() + " append(s): " + acknowledged
                                 + " acknowledged, " + ambiguous.size()
                                 + " event(s) left ambiguous by a failure that was not a decision, of which "
                                 + ambiguous.stream().filter(timesStored::containsKey).count()
                                 + " turned out to be stored.");

        String declared = history.header().workloadShape().getOrDefault("declaredFaults", "");
        boolean ambiguityWasSought = AMBIGUITY_MAKING_FAULTS.stream().anyMatch(declared::contains);
        if (ambiguityWasSought && ambiguous.isEmpty()) {
            notes.add("The run declared " + declared + ", whose purpose is to make an acknowledgement ambiguous, and "
                              + "produced no ambiguous append at all: the fault did not land inside a commit window, so "
                              + "nothing about durability under that fault has been tested.");
        }
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes), List.copyOf(measurements),
                               List.of());
    }

    /**
     * Indicates whether the store, or a fault standing in for it, actually decided against this append.
     * <p>
     * Anything else is a conversation that was lost rather than an answer that was given, and the store is not bound by
     * it.
     */
    private static boolean decided(Operation append) {
        HistoryRecord completion = append.completion();
        if (completion == null) {
            return false;
        }
        String error = completion.error();
        return ModelConformanceChecker.CONSISTENCY_REJECTION.equals(error)
                || "InjectedStoreFailureException".equals(error);
    }

    private static List<String> eventIdentifiersOf(Operation append) {
        return DcbHistoryCodec.decodeEvents(append.invocation().value()).stream().map(ModelEvent::id).toList();
    }

    private static String describe(List<String> missing, List<String> repeated, Map<String, Integer> timesStored) {
        StringBuilder detail = new StringBuilder("an append the client saw succeed is not in the store as it should be");
        if (!missing.isEmpty()) {
            detail.append("; missing ").append(missing);
        }
        if (!repeated.isEmpty()) {
            detail.append("; stored more than once ")
                  .append(repeated.stream().map(id -> id + " x" + timesStored.get(id)).toList());
        }
        return detail.toString();
    }
}
