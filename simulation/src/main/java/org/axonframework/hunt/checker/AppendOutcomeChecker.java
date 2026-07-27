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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the two things an append's outcome must always satisfy, whatever the reference model says.
 * <p>
 * The first is the control arm. An append made without a consistency condition claims no boundary at all, so nothing
 * can conflict with it and the store has nothing to reject it for. If one is ever rejected, the conflict check is
 * running where it should not be, and every conclusion drawn from the run's rejection counts is suspect.
 * <p>
 * The second is all-or-nothing. A rejected append must leave the store exactly as it found it, so none of the events
 * it offered may turn up in the authoritative scan taken after the run has quiesced. A partial write behind a
 * rejection is silent corruption: the caller was told the write did not happen.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public class AppendOutcomeChecker implements Checker {

    /**
     * The stable name of the invariant that an unconditional append always succeeds.
     */
    public static final String UNCONDITIONAL_APPEND_NEVER_REJECTED = "UnconditionalAppendNeverRejected";

    /**
     * The statement of {@link #UNCONDITIONAL_APPEND_NEVER_REJECTED}, character-identical to the invariant registry.
     */
    public static final String UNCONDITIONAL_APPEND_NEVER_REJECTED_STATEMENT =
            "An append made without a consistency condition is never rejected as conflicting.";

    /**
     * The stable name of the invariant that a rejected append stores nothing.
     */
    public static final String REJECTED_APPEND_LEAVES_NO_EVENTS = "RejectedAppendLeavesNoEvents";

    /**
     * The statement of {@link #REJECTED_APPEND_LEAVES_NO_EVENTS}, character-identical to the invariant registry.
     */
    public static final String REJECTED_APPEND_LEAVES_NO_EVENTS_STATEMENT =
            "No event offered by an append recorded as rejected is present in the authoritative scan taken after the "
                    + "run has quiesced.";

    @Override
    public String name() {
        return "AppendOutcomeChecker";
    }

    @Override
    public Set<String> machineNames() {
        return Set.of(UNCONDITIONAL_APPEND_NEVER_REJECTED, REJECTED_APPEND_LEAVES_NO_EVENTS);
    }

    @Override
    public CheckResult check(HistoryView history) {
        List<Operation> appends = history.operations(HistoryOps.APPEND);
        if (appends.isEmpty()) {
            return CheckResult.holding(name());
        }
        List<Violation> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        List<HistoryRecord> scans = history.notes(HistoryOps.SCAN);
        Set<String> stored = new HashSet<>();
        boolean scanned = !scans.isEmpty();
        if (scanned) {
            stored.addAll(scans.getLast().stringListValue(DcbHistoryCodec.EVENT_IDS));
        } else {
            notes.add("The run recorded no authoritative scan, so what a rejected append left behind is unknown.");
        }

        for (Operation append : appends) {
            boolean unconditional =
                    append.invocation().longValue(DcbHistoryCodec.MARKER, DcbStoreModel.ORIGIN) == DcbStoreModel.INFINITY;
            boolean rejected = append.outcome() == Outcome.FAIL
                    && ModelConformanceChecker.CONSISTENCY_REJECTION.equals(errorOf(append));

            if (unconditional && rejected) {
                violations.add(Violation.of(UNCONDITIONAL_APPEND_NEVER_REJECTED,
                                            UNCONDITIONAL_APPEND_NEVER_REJECTED_STATEMENT,
                                            "an append anchored at the end of the stream was rejected as conflicting",
                                            append.records(),
                                            history.header()));
            }
            if (scanned && append.outcome() == Outcome.FAIL) {
                List<String> leaked = DcbHistoryCodec.decodeEvents(append.invocation().value())
                                                     .stream()
                                                     .map(org.axonframework.hunt.model.ModelEvent::id)
                                                     .filter(stored::contains)
                                                     .toList();
                if (!leaked.isEmpty()) {
                    violations.add(Violation.of(REJECTED_APPEND_LEAVES_NO_EVENTS,
                                                REJECTED_APPEND_LEAVES_NO_EVENTS_STATEMENT,
                                                "a rejected append left " + leaked + " in the store",
                                                append.records(),
                                                history.header()));
                }
            }
        }
        return new CheckResult(name(), List.copyOf(violations), List.copyOf(notes));
    }

    private static @org.jspecify.annotations.Nullable String errorOf(Operation operation) {
        return operation.completion() == null ? null : operation.completion().error();
    }
}
