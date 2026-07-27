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
import org.axonframework.hunt.model.DcbStoreModel;
import org.axonframework.hunt.model.ModelAppendCondition;
import org.axonframework.hunt.model.ModelEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays a history against the {@link DcbStoreModel} and reports where the store and the model disagreed.
 * <p>
 * This is the suite's primary oracle. Every append the run recorded as successful must be one the model accepts at
 * that point in the history, and every append recorded as rejected must be one the model rejects. An append whose
 * outcome the run could not determine is allowed to be either, because it genuinely is.
 * <p>
 * An indeterminate append leaves the replay ambiguous: the model applies it when it would be legal, so the replay can
 * continue, but from that point on a disagreement is reported as a note rather than as a violation. The alternative,
 * asserting against a state that might not be the store's, is how a history-checked suite invents findings.
 * <p>
 * Two other situations make the replay ambiguous rather than broken. An append that failed for a reason other than
 * the store's own consistency check carries no protocol verdict at all, so it is skipped: an injected infrastructure
 * failure is not the protocol saying no. And a run in which a fault made the store hold something other than what was
 * offered cannot be replayed at all, because the model reproduces what the workload asked for and the store
 * deliberately does not hold that.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class ModelConformanceChecker implements Checker {

    /**
     * The stable name of the invariant this checker enforces.
     */
    public static final String APPEND_CONFORMS_TO_DCB_MODEL = "AppendConformsToDcbModel";

    /**
     * The statement of {@link #APPEND_CONFORMS_TO_DCB_MODEL}, character-identical to the invariant registry.
     */
    public static final String APPEND_CONFORMS_TO_DCB_MODEL_STATEMENT =
            "Every append recorded as successful is accepted by the DCB reference model at its point in the history, "
                    + "and every append recorded as rejected is rejected by it.";

    /**
     * The error an append is recorded under when the store's own consistency check refused it.
     * <p>
     * A failure recorded under any other error means the append did not land for a reason the reference model knows
     * nothing about, and carries no protocol verdict to conform to.
     */
    public static final String CONSISTENCY_REJECTION = "AppendEventsTransactionRejectedException";

    @Override
    public String name() {
        return "ModelConformanceChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(APPEND_CONFORMS_TO_DCB_MODEL);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<Operation> appends = history.operations(HistoryOps.APPEND);
        List<Violation> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean ambiguous = false;

        if (!history.unpairedCompletions().isEmpty()) {
            notes.add("The history has " + history.unpairedCompletions().size()
                              + " completion(s) with no invocation; the replay may not reflect the store's state.");
            ambiguous = true;
        }
        int perturbed = history.notes(HistoryOps.STORE_PERTURBED).size();
        if (perturbed > 0) {
            notes.add("A fault made the store hold something other than what was offered, on " + perturbed
                              + " commit(s); the replay cannot reflect the store's state.");
            ambiguous = true;
        }

        Map<String, Operation> offeredBy = new HashMap<>();
        for (Operation append : appends) {
            for (ModelEvent event : DcbHistoryCodec.decodeEvents(append.invocation().value())) {
                offeredBy.putIfAbsent(event.id(), append);
            }
        }

        DcbStoreModel model = new DcbStoreModel();
        Set<String> replayed = new LinkedHashSet<>();
        int unattributed = 0;
        int acknowledgedButAbsent = 0;
        for (String eventId : storeOrder(history, appends)) {
            Operation append = offeredBy.get(eventId);
            if (append == null) {
                unattributed++;
                ambiguous = true;
                continue;
            }
            if (!replayed.add(append.id())) {
                continue;
            }
            ModelAppendCondition condition = DcbHistoryCodec.decodeCondition(append.invocation().value());
            List<ModelEvent> batch = DcbHistoryCodec.decodeEvents(append.invocation().value());
            boolean accepts = model.wouldAccept(condition);
            if (append.outcome() == Outcome.UNKNOWN) {
                notes.add("Append " + append.id() + " (record #" + append.invocation().idx()
                                  + ") has an unknown outcome; the replayed store state is ambiguous from here.");
                ambiguous = true;
                if (!accepts) {
                    continue;
                }
            } else if (!accepts) {
                ambiguous = report(history, append, ambiguous, violations, notes,
                                   "an append the model rejects as conflicting was recorded as successful");
            }
            model.append(condition, batch);
        }

        for (Operation append : appends) {
            if (replayed.contains(append.id())) {
                continue;
            }
            List<ModelEvent> batch = DcbHistoryCodec.decodeEvents(append.invocation().value());
            ModelAppendCondition condition = DcbHistoryCodec.decodeCondition(append.invocation().value());
            switch (append.outcome()) {
                case OK -> {
                    if (!batch.isEmpty()) {
                        acknowledgedButAbsent++;
                        ambiguous = true;
                    }
                }
                case FAIL -> {
                    // Only the store's own consistency check carries a verdict the model can be held to; any other
                    // failure means the append did not land for a reason outside the protocol. A conflict is monotone
                    // in a growing store, so a rejection the model still accepts against the final state was never
                    // legitimate at any earlier point either.
                    if (CONSISTENCY_REJECTION.equals(errorOf(append)) && model.wouldAccept(condition)) {
                        ambiguous = report(history, append, ambiguous, violations, notes,
                                           "an append the model accepts was recorded as rejected");
                    }
                }
                case UNKNOWN -> {
                    if (!batch.isEmpty()) {
                        acknowledgedButAbsent++;
                        ambiguous = true;
                    }
                }
            }
        }
        if (unattributed > 0) {
            notes.add(unattributed + " event(s) are in the store although no recorded append offered them.");
        }
        if (acknowledgedButAbsent > 0) {
            notes.add(acknowledgedButAbsent + " append(s) were acknowledged, yet none of their events are in the "
                              + "store.");
        }
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes));
    }

    /**
     * Returns the order the store applied its appends in.
     * <p>
     * A concurrent run cannot be replayed in the order its operations were issued: two writers race, and the one that
     * asked first is often not the one that landed first. The authoritative scan taken after the run has quiesced is
     * the store's own answer to that question, and replaying against it is the difference between a real conflict
     * oracle and one that reports every race as a defect.
     * <p>
     * A history with no scan in it -- a hand-written one, or a run that ended before it could scan -- falls back to
     * the order the appends completed in, which is exact for a sequential history and the best available guess for
     * any other.
     *
     * @param history the recorded run
     * @param appends the run's appends
     * @return the identifiers of the stored events, in the order the store holds them
     */
    private static List<String> storeOrder(HistoryView history, List<Operation> appends) {
        List<HistoryRecord> scans = history.notes(HistoryOps.SCAN);
        if (!scans.isEmpty()) {
            return scans.getLast().stringListValue(DcbHistoryCodec.EVENT_IDS);
        }
        return appends.stream()
                      .filter(append -> append.outcome() != Outcome.FAIL)
                      .sorted(Comparator.comparingLong(ModelConformanceChecker::settledAt))
                      .flatMap(append -> DcbHistoryCodec.decodeEvents(append.invocation().value()).stream()
                                                        .map(ModelEvent::id))
                      .toList();
    }

    private static long settledAt(Operation operation) {
        return operation.completion() == null ? operation.invocation().idx() : operation.completion().idx();
    }

    private static @org.jspecify.annotations.Nullable String errorOf(Operation operation) {
        return operation.completion() == null ? null : operation.completion().error();
    }

    private boolean report(HistoryView history,
                           Operation operation,
                           boolean ambiguous,
                           List<Violation> violations,
                           List<String> notes,
                           String detail) {
        if (ambiguous) {
            // The replayed state is no longer known to match the store, so a mismatch cannot support an assertion.
            notes.add("Undecidable after an ambiguous append: " + detail + " at record #"
                              + operation.invocation().idx() + ".");
            return true;
        }
        violations.add(violation(history, operation, detail));
        return false;
    }

    private Violation violation(HistoryView history, Operation operation, String detail) {
        return Violation.of(APPEND_CONFORMS_TO_DCB_MODEL,
                            APPEND_CONFORMS_TO_DCB_MODEL_STATEMENT,
                            detail,
                            operation.records(),
                            history.header());
    }
}
