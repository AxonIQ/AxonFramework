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
import org.axonframework.hunt.history.HistoryView;
import org.axonframework.hunt.history.Operation;
import org.axonframework.hunt.model.DcbHistoryCodec;
import org.axonframework.hunt.model.DcbStoreModel;
import org.axonframework.hunt.model.ModelAppendCondition;
import org.axonframework.hunt.model.ModelEvent;

import java.util.ArrayList;
import java.util.List;
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
        DcbStoreModel model = new DcbStoreModel();
        List<Violation> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean ambiguous = false;

        if (!history.unpairedCompletions().isEmpty()) {
            notes.add("The history has " + history.unpairedCompletions().size()
                              + " completion(s) with no invocation; the replay may not reflect the store's state.");
            ambiguous = true;
        }

        for (Operation operation : history.operations(HistoryOps.APPEND)) {
            ModelAppendCondition condition = DcbHistoryCodec.decodeCondition(operation.invocation().value());
            List<ModelEvent> batch = DcbHistoryCodec.decodeEvents(operation.invocation().value());
            boolean modelAccepts = model.wouldAccept(condition);

            switch (operation.outcome()) {
                case OK -> {
                    if (!modelAccepts) {
                        report(history, operation, ambiguous, violations, notes,
                               "an append the model rejects as conflicting was recorded as successful");
                    }
                    model.append(condition, batch);
                }
                case FAIL -> {
                    if (modelAccepts) {
                        report(history, operation, ambiguous, violations, notes,
                               "an append the model accepts was recorded as rejected");
                    }
                }
                case UNKNOWN -> {
                    notes.add("Append " + operation.id() + " (record #" + operation.invocation().idx()
                                      + ") has an unknown outcome; the replayed store state is ambiguous from here.");
                    ambiguous = true;
                    if (modelAccepts) {
                        model.append(condition, batch);
                    }
                }
            }
        }
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes));
    }

    private void report(HistoryView history,
                        Operation operation,
                        boolean ambiguous,
                        List<Violation> violations,
                        List<String> notes,
                        String detail) {
        if (ambiguous) {
            // The replayed state is no longer known to match the store, so a mismatch cannot support an assertion.
            notes.add("Undecidable after an ambiguous append: " + detail + " at record #"
                              + operation.invocation().idx() + ".");
            return;
        }
        violations.add(violation(history, operation, detail));
    }

    private Violation violation(HistoryView history, Operation operation, String detail) {
        return Violation.of(APPEND_CONFORMS_TO_DCB_MODEL,
                            APPEND_CONFORMS_TO_DCB_MODEL_STATEMENT,
                            detail,
                            operation.records(),
                            history.header());
    }
}
